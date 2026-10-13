package io.github.yuri_hack.rag_knowledge_qa.agent;

import io.github.yuri_hack.rag_knowledge_qa.dto.response.UsageInfo;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 一次 agent 会话的完整轨迹：每步的 usage、工具调用、终止原因、总成本。
 * 这是 D3 成本指标和 D4 badcases 的数据来源。
 */
@Getter
public class AgentTrace {

    public enum TerminationReason { COMPLETED, MAX_STEPS_REACHED, LLM_FAILURE }

    private static final String DEFAULT_PROMPT_VERSION = "unknown";

    private final String question;
    private final List<AgentStep> steps = new ArrayList<>();
    private final long startedAtMs = System.currentTimeMillis();

    private String finalAnswer;
    private TerminationReason terminationReason;
    private long endedAtMs;
    private String promptVersion = DEFAULT_PROMPT_VERSION;

    public AgentTrace(String question) {
        this.question = question;
    }

    public AgentStep addStep(int stepNo, UsageInfo usage) {
        AgentStep step = new AgentStep(stepNo, usage);
        steps.add(step);
        return step;
    }

    public void complete(String answer) {
        this.finalAnswer = answer;
        this.terminationReason = TerminationReason.COMPLETED;
        this.endedAtMs = System.currentTimeMillis();
    }

    public void maxStepsReached(String forcedAnswer) {
        this.finalAnswer = forcedAnswer;
        this.terminationReason = TerminationReason.MAX_STEPS_REACHED;
        this.endedAtMs = System.currentTimeMillis();
    }

    public void llmFailed(String message) {
        this.finalAnswer = message;
        this.terminationReason = TerminationReason.LLM_FAILURE;
        this.endedAtMs = System.currentTimeMillis();
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public int getInputTokens() {
        return sumTokens(UsageInfo::getInputTokens);
    }

    public int getOutputTokens() {
        return sumTokens(UsageInfo::getOutputTokens);
    }

    public int getTotalTokens() {
        return sumTokens(UsageInfo::getTotalTokens);
    }

    /** 逐步累加 token 数；usage 或对应字段为 null 的步按 0 计。 */
    private int sumTokens(Function<UsageInfo, Integer> tokenGetter) {
        return steps.stream()
                .map(AgentStep::getUsage)
                .filter(Objects::nonNull)
                .map(tokenGetter)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }

    public long getDurationMs() {
        return (endedAtMs == 0 ? System.currentTimeMillis() : endedAtMs) - startedAtMs;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder("\n===== AgentTrace =====\n");
        sb.append("[Q] ").append(question).append('\n');
        for (AgentStep step : steps) {
            appendStepLine(sb, step);
        }
        sb.append("[answer] ").append(finalAnswer == null ? "(无)" : finalAnswer.replaceAll("\\s+", " ")).append('\n');
        appendStatsLine(sb);
        return sb.toString();
    }

    private void appendStepLine(StringBuilder sb, AgentStep step) {
        sb.append("[step ").append(step.getStep()).append("] ");
        if (step.getToolCalls().isEmpty()) {
            sb.append("无 tool_call → 终止");
        } else {
            for (AgentStep.ToolCallRecord toolCall : step.getToolCalls()) {
                sb.append("tool_call ").append(toolCall.getName())
                        .append(' ').append(toolCall.getArguments())
                        .append(" → ").append(toolCall.getResultExcerpt())
                        .append(toolCall.isSuccess() ? "" : " [失败]")
                        .append("  ");
            }
        }
        UsageInfo usage = step.getUsage();
        if (usage != null) {
            sb.append(" (tokens in=").append(usage.getInputTokens())
                    .append(" out=").append(usage.getOutputTokens())
                    .append(" total=").append(usage.getTotalTokens()).append(')');
        }
        sb.append('\n');
    }

    private void appendStatsLine(StringBuilder sb) {
        sb.append("[stats] 终止=").append(terminationReason)
                .append(" 步数=").append(steps.size())
                .append(" 总tokens=").append(getTotalTokens())
                .append(" prompt=").append(promptVersion)
                .append(" 耗时=").append(getDurationMs()).append("ms\n");
    }
}
