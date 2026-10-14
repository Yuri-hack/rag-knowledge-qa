package io.github.d3kstra.rag_knowledge_qa.agent;

import io.github.d3kstra.rag_knowledge_qa.agent.model.AgentStep;
import io.github.d3kstra.rag_knowledge_qa.agent.model.AgentTrace;
import io.github.d3kstra.rag_knowledge_qa.agent.tool.KnowledgeSearchTool;
import io.github.d3kstra.rag_knowledge_qa.agent.tool.WebSearchTool;

import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.google.gson.JsonObject;
import io.github.d3kstra.rag_knowledge_qa.service.base.AskResult;
import io.github.d3kstra.rag_knowledge_qa.service.base.BaseTongYiService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Agent 主循环：ask → 有 tool_call 执行并回传后继续，无则终止；失败/步数上限/usage 记入 AgentTrace。 */
@Component
public class AgentLoop {

    private final TongYiAgentService agentService;
    private final ToolRegistry toolRegistry;

    public AgentLoop(TongYiAgentService agentService, ToolRegistry toolRegistry) {
        this.agentService = agentService;
        this.toolRegistry = toolRegistry;
    }

    /** maxSteps 硬上限（HTTP 可传参，必须有界） */
    private static final int MAX_STEPS_LIMIT = 10;

    /** 检索预算超限前缀 */
    private static final String SEARCH_BUDGET_MARKER = "[检索预算已用完]";

    /** 按次计费工具的预算表（prompt 约定在此强制）；web_search 含搜索费，预算收紧 */
    private static final Map<String, Integer> TOOL_BUDGETS = Map.of(
            KnowledgeSearchTool.NAME, AgentPrompts.SEARCH_BUDGET,
            WebSearchTool.NAME, AgentPrompts.WEB_SEARCH_BUDGET);

    /** dashscope 仅 function 一种 tool_call */
    private static final String TOOL_CALL_TYPE_FUNCTION = "function";

    /** 工具结果摘要长度 */
    private static final int RESULT_EXCERPT_MAX_CHARS = 80;

    public AgentTrace run(String question, String systemPrompt, int maxSteps) {
        maxSteps = Math.min(maxSteps, MAX_STEPS_LIMIT);
        AgentTrace trace = newTrace(question, systemPrompt);
        List<ToolBase> tools = toolRegistry.toToolFunctions();
        // 检索预算计数器：prompt 约定在此强制
        Map<String, Integer> toolUsage = new HashMap<>();

        List<Message> messages = initialMessages(systemPrompt, question);

        for (int stepNo = 1; stepNo <= maxSteps; stepNo++) {
            AskResult ask = agentService.ask(messages, tools);
            Message reply = ask.message();
            AgentStep step = trace.addStep(stepNo, ask.usage());

            // LLM 失败：终止，不重试
            if (reply.getToolCalls() == null && isLlmFailure(reply.getContent())) {
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

            // 有 tool_call：入历史并逐个执行
            messages.add(reply);
            for (ToolCallBase toolCall : reply.getToolCalls()) {
                executeToolCall(toolCall, step, toolUsage, messages);
            }
        }
        // 步数打满：强制作答一次
        return forceAnswerWithoutTools(messages, trace, maxSteps);
    }

    /** B 臂对照：单次检索 + 同 prompt 无工具生成，隔离提示词与循环的贡献。 */
    public AgentTrace runSingleShot(String question, String systemPrompt) {
        AgentTrace trace = newTrace(question, systemPrompt);

        AgentStep step1 = trace.addStep(1, null);
        JsonObject args = new JsonObject();
        args.addProperty("query", question);
        String result = toolRegistry.execute(KnowledgeSearchTool.NAME, args.toString());
        step1.addToolCall(KnowledgeSearchTool.NAME, question, excerpt(result), !ToolRegistry.isFailure(result));

        List<Message> messages = initialMessages(systemPrompt, question + "\n\n【检索结果】\n" + result);

        AskResult ask = agentService.ask(messages, List.of());
        AgentStep step2 = trace.addStep(2, ask.usage());
        String content = ask.message().getContent();
        step2.setContent(content);
        // LLM 故障不记 COMPLETED
        if (isLlmFailure(content)) {
            trace.llmFailed(content);
        } else {
            trace.complete(content);
        }
        return trace;
    }

    private static AgentTrace newTrace(String question, String systemPrompt) {
        AgentTrace trace = new AgentTrace(question);
        trace.setPromptVersion(AgentPrompts.versionOf(systemPrompt));
        return trace;
    }

    private static List<Message> initialMessages(String systemPrompt, String userContent) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().role(Role.SYSTEM.getValue()).content(systemPrompt).build());
        messages.add(Message.builder().role(Role.USER.getValue()).content(userContent).build());
        return messages;
    }

    /** 执行单条 tool_call，结果以 tool 角色回传 */
    private void executeToolCall(ToolCallBase toolCall, AgentStep step,
                                 Map<String, Integer> toolUsage, List<Message> messages) {
        if (!TOOL_CALL_TYPE_FUNCTION.equals(toolCall.getType())) {
            return;
        }
        ToolCallFunction functionCall = (ToolCallFunction) toolCall;
        String toolName = functionCall.getFunction().getName();
        String argumentsJson = functionCall.getFunction().getArguments();

        String result = executeWithSearchBudget(toolName, argumentsJson, toolUsage);
        boolean success = !ToolRegistry.isFailure(result)
                && !result.startsWith(SEARCH_BUDGET_MARKER);
        step.addToolCall(toolName, argumentsJson, excerpt(result), success);

        messages.add(Message.builder()
                .role(Role.TOOL.getValue())
                .content(result)
                .toolCallId(toolCall.getId())
                .build());
    }

    /** 步数打满：摘掉工具强制作答 */
    private AgentTrace forceAnswerWithoutTools(List<Message> messages, AgentTrace trace, int maxSteps) {
        messages.add(Message.builder().role(Role.USER.getValue())
                .content("已达到检索步数上限。请基于以上已检索到的全部信息直接回答最初的问题，"
                        + "并明确说明仍有不足之处，不要再调用任何工具。")
                .build());
        AskResult forced = agentService.ask(messages, List.of());
        AgentStep finalStep = trace.addStep(maxSteps + 1, forced.usage());
        String forcedContent = forced.message().getContent();
        finalStep.setContent(forcedContent);
        // LLM 故障不记 MAX_STEPS
        if (isLlmFailure(forcedContent)) {
            trace.llmFailed(forcedContent);
        } else {
            trace.maxStepsReached(forcedContent);
        }
        return trace;
    }

    /** 按次计费工具计数强制预算，超出返回固定文本。 */
    private String executeWithSearchBudget(String name, String argumentsJson, Map<String, Integer> usage) {
        Integer budget = TOOL_BUDGETS.get(name);
        if (budget != null && usage.merge(name, 1, Integer::sum) > budget) {
            return SEARCH_BUDGET_MARKER + " " + name + " 已调用满 " + budget + " 次，"
                    + "请基于已获得的全部信息直接回答最初的问题，并说明仍有不足之处，不要再调用任何工具。";
        }
        return toolRegistry.execute(name, argumentsJson);
    }

    /** ask() 失败协议见 {@link BaseTongYiService#LLM_FAILURE_MARKER} */
    private static boolean isLlmFailure(String content) {
        return content != null && content.startsWith(BaseTongYiService.LLM_FAILURE_MARKER);
    }

    private static String excerpt(String s) {
        if (s == null) return "(null)";
        String firstLine = s.split("\n")[0];
        return firstLine.length() > RESULT_EXCERPT_MAX_CHARS
                ? firstLine.substring(0, RESULT_EXCERPT_MAX_CHARS) + "…" : firstLine;
    }
}
