package io.github.yuri_hack.rag_knowledge_qa.agent;

import io.github.yuri_hack.rag_knowledge_qa.dto.response.UsageInfo;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次 agent 会话的完整轨迹：每步的 usage、工具调用、终止原因、总成本。
 * 这是 D3 成本指标和 D4 badcases 的数据来源。
 */
@Getter
public class AgentTrace {

    public enum TerminationReason { COMPLETED, MAX_STEPS_REACHED, LLM_FAILURE }

    private final String question;
    private final List<AgentStep> steps = new ArrayList<>();
    private final long startedAtMs = System.currentTimeMillis();

    private String finalAnswer;
    private TerminationReason terminationReason;
    private long endedAtMs;

    public AgentTrace(String question) {
        this.question = question;
    }

    public AgentStep addStep(int stepNo, UsageInfo usage) {
        AgentStep s = new AgentStep(stepNo, usage);
        steps.add(s);
        return s;
    }

    public void complete(String answer) {
        this.finalAnswer = answer;
        this.terminationReason = TerminationReason.COMPLETED;
        this.endedAtMs = System.currentTimeMillis();
    }

    public void maxStepsReached() {
        this.terminationReason = TerminationReason.MAX_STEPS_REACHED;
        this.endedAtMs = System.currentTimeMillis();
    }

    public void llmFailed(String message) {
        this.finalAnswer = message;
        this.terminationReason = TerminationReason.LLM_FAILURE;
        this.endedAtMs = System.currentTimeMillis();
    }

    public int getTotalTokens() {
        return steps.stream()
                .mapToInt(s -> s.getUsage() == null || s.getUsage().getTotalTokens() == null
                        ? 0 : s.getUsage().getTotalTokens())
                .sum();
    }

    public long getDurationMs() {
        return (endedAtMs == 0 ? System.currentTimeMillis() : endedAtMs) - startedAtMs;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder("\n===== AgentTrace =====\n");
        sb.append("[Q] ").append(question).append('\n');
        for (AgentStep s : steps) {
            sb.append("[step ").append(s.getStep()).append("] ");
            if (s.getToolCalls().isEmpty()) {
                sb.append("无 tool_call → 终止");
            } else {
                for (AgentStep.ToolCallRecord tc : s.getToolCalls()) {
                    sb.append("tool_call ").append(tc.getName())
                            .append(' ').append(tc.getArguments())
                            .append(" → ").append(tc.getResultExcerpt())
                            .append(tc.isSuccess() ? "" : " [失败]")
                            .append("  ");
                }
            }
            UsageInfo u = s.getUsage();
            if (u != null) {
                sb.append(" (tokens in=").append(u.getInputTokens())
                        .append(" out=").append(u.getOutputTokens())
                        .append(" total=").append(u.getTotalTokens()).append(')');
            }
            sb.append('\n');
        }
        sb.append("[answer] ").append(finalAnswer == null ? "(无)" : finalAnswer.replaceAll("\\s+", " ")).append('\n');
        sb.append("[stats] 终止=").append(terminationReason)
                .append(" 步数=").append(steps.size())
                .append(" 总tokens=").append(getTotalTokens())
                .append(" 耗时=").append(getDurationMs()).append("ms\n");
        return sb.toString();
    }
}
