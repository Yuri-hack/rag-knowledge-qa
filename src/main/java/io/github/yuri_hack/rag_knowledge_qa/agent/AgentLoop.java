package io.github.yuri_hack.rag_knowledge_qa.agent;

import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.alibaba.dashscope.utils.JsonUtils;
import io.github.yuri_hack.rag_knowledge_qa.service.base.AskResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 正式 agent 循环（D2）：
 *   messages = [system, user]
 *   循环（≤ maxSteps）:
 *     ask(messages, tools) → 无 tool_call 则终止并返回答案
 *                          → 有 tool_call 则执行、结果以 tool 角色回传、进入下一步
 *
 * 边界处理：步数上限防绕圈 / 工具失败由 ToolRegistry 转文本回传 /
 *          LLM 调用失败标记 LLM_FAILURE 终止 / 每步 usage 进 AgentTrace。
 */
@Component
public class AgentLoop {

    private final TongYiAgentService agentService;
    private final ToolRegistry toolRegistry;

    public AgentLoop(TongYiAgentService agentService, ToolRegistry toolRegistry) {
        this.agentService = agentService;
        this.toolRegistry = toolRegistry;
    }

    /** maxSteps 硬上限：暴露在 HTTP 参数上的成本开关，必须有界（检索预算只限 search_knowledge，
     *  get_document_detail 无预算，无上限时一次请求可拖起数千次 LLM 调用） */
    private static final int MAX_STEPS_LIMIT = 10;

    public AgentTrace run(String question, String systemPrompt, int maxSteps) {
        maxSteps = Math.min(maxSteps, MAX_STEPS_LIMIT);
        AgentTrace trace = new AgentTrace(question);
        trace.setPromptVersion(AgentPrompts.versionOf(systemPrompt));
        List<ToolBase> tools = toolRegistry.toToolFunctions();
        // 检索预算计数器（按 tool name 计次）：prompt 里的预算只是约定，这里是强制
        Map<String, Integer> toolUsage = new HashMap<>();

        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().role(Role.SYSTEM.getValue()).content(systemPrompt).build());
        messages.add(Message.builder().role(Role.USER.getValue()).content(question).build());

        for (int stepNo = 1; stepNo <= maxSteps; stepNo++) {
            AskResult ask = agentService.ask(messages, tools);
            Message reply = ask.message();
            AgentStep step = trace.addStep(stepNo, ask.usage());

            // LLM 调用失败（ask 已把异常吞成失败文本）：标记终止，不无限重试
            if (reply.getToolCalls() == null && reply.getContent() != null
                    && reply.getContent().startsWith("[模型调用失败]")) {
                step.setContent(reply.getContent());
                trace.llmFailed(reply.getContent());
                return trace;
            }

            // 无 tool_call：正常终止
            if (reply.getToolCalls() == null || reply.getToolCalls().isEmpty()) {
                step.setContent(reply.getContent());
                trace.complete(reply.getContent());
                return trace;
            }

            // 有 tool_call：assistant 消息原样入历史，逐个执行
            messages.add(reply);
            for (ToolCallBase tc : reply.getToolCalls()) {
                if (!"function".equals(tc.getType())) continue;
                ToolCallFunction fn = (ToolCallFunction) tc;
                String fnName = fn.getFunction().getName();
                String fnArgs = fn.getFunction().getArguments();

                String result = executeWithSearchBudget(fnName, fnArgs, toolUsage);
                boolean success = !result.startsWith("[工具执行失败]") && !result.startsWith("[未知工具]")
                        && !result.startsWith("[检索预算已用完]");
                step.addToolCall(fnName, fnArgs, excerpt(result), success);

                messages.add(Message.builder()
                        .role("tool")
                        .content(result)
                        .toolCallId(tc.getId())
                        .build());
            }
        }
        // BC01 修复：步数打满时不再空手终止——用已检索的证据强制作答一次
        messages.add(Message.builder().role(Role.USER.getValue())
                .content("已达到检索步数上限。请基于以上已检索到的全部信息直接回答最初的问题，"
                        + "并明确说明仍有不足之处，不要再调用任何工具。")
                .build());
        AskResult forced = agentService.ask(messages, List.of());
        AgentStep finalStep = trace.addStep(maxSteps + 1, forced.usage());
        String forcedContent = forced.message().getContent();
        // 与主循环对称的失败检测：LLM 故障不能记成"步数打满"（排障/评测口径都会被带偏）
        if (forcedContent != null && forcedContent.startsWith("[模型调用失败]")) {
            finalStep.setContent(forcedContent);
            trace.llmFailed(forcedContent);
            return trace;
        }
        finalStep.setContent(forcedContent);
        trace.maxStepsReached(forcedContent);
        return trace;
    }

    /**
     * B 臂（单次 RAG + 同 prompt）：强制一次检索（与 agent 相同的工具与 topK），然后无工具生成。
     * 用于隔离"提示词/检索质量"与"多轮动作空间"的贡献。
     */
    public AgentTrace runSingleShot(String question, String systemPrompt) {
        AgentTrace trace = new AgentTrace(question);
        trace.setPromptVersion(AgentPrompts.versionOf(systemPrompt));

        AgentStep step1 = trace.addStep(1, null);
        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("query", question);
        String result = toolRegistry.execute("search_knowledge", args.toString());
        step1.addToolCall("search_knowledge", question, excerpt(result), !result.startsWith("["));

        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().role(Role.SYSTEM.getValue()).content(systemPrompt).build());
        messages.add(Message.builder().role(Role.USER.getValue())
                .content(question + "\n\n【检索结果】\n" + result).build());

        AskResult ask = agentService.ask(messages, List.of());
        AgentStep step2 = trace.addStep(2, ask.usage());
        step2.setContent(ask.message().getContent());
        trace.complete(ask.message().getContent());
        return trace;
    }

    /**
     * BC01 预算显式化的工程化收尾：prompt 的"search_knowledge 最多 3 次"只是约定，
     * 这里按 tool name 计数强制执行——超预算不执行检索，返回固定文本让模型
     * 按"失败即输入"基于已有信息作答。
     */
    private String executeWithSearchBudget(String name, String argumentsJson, Map<String, Integer> usage) {
        if ("search_knowledge".equals(name)
                && usage.merge(name, 1, Integer::sum) > AgentPrompts.SEARCH_BUDGET) {
            return "[检索预算已用完] search_knowledge 已调用满 " + AgentPrompts.SEARCH_BUDGET + " 次，"
                    + "请基于已获得的全部信息直接回答最初的问题，并说明仍有不足之处，不要再调用任何工具。";
        }
        return toolRegistry.execute(name, argumentsJson);
    }

    private static String excerpt(String s) {
        if (s == null) return "(null)";
        String firstLine = s.split("\n")[0];
        return firstLine.length() > 80 ? firstLine.substring(0, 80) + "…" : firstLine;
    }
}
