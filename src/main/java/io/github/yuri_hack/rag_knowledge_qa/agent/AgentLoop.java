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
import java.util.List;

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

    public AgentTrace run(String question, String systemPrompt, int maxSteps) {
        AgentTrace trace = new AgentTrace(question);
        List<ToolBase> tools = toolRegistry.toToolFunctions();

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

                String result = toolRegistry.execute(fnName, fnArgs);
                boolean success = !result.startsWith("[工具执行失败]") && !result.startsWith("[未知工具]");
                step.addToolCall(fnName, fnArgs, excerpt(result), success);

                messages.add(Message.builder()
                        .role("tool")
                        .content(result)
                        .toolCallId(tc.getId())
                        .build());
            }
        }
        trace.maxStepsReached();
        return trace;
    }

    /**
     * B 臂（单次 RAG + 同 prompt）：强制一次检索（与 agent 相同的工具与 topK），然后无工具生成。
     * 用于隔离"提示词/检索质量"与"多轮动作空间"的贡献。
     */
    public AgentTrace runSingleShot(String question, String systemPrompt) {
        AgentTrace trace = new AgentTrace(question);

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

    private static String excerpt(String s) {
        if (s == null) return "(null)";
        String firstLine = s.split("\n")[0];
        return firstLine.length() > 80 ? firstLine.substring(0, 80) + "…" : firstLine;
    }
}
