package io.github.yuri_hack.rag_knowledge_qa.agent;

import io.github.yuri_hack.rag_knowledge_qa.dto.response.UsageInfo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 轨迹记账：token 累加容错与三种终止态。 */
class AgentTraceTest {

    private static UsageInfo usage(Integer in, Integer out, Integer total) {
        return UsageInfo.builder().inputTokens(in).outputTokens(out).totalTokens(total).build();
    }

    @Test
    void token按步累加且忽略空值() {
        AgentTrace trace = new AgentTrace("q");
        trace.addStep(1, usage(10, 5, 15));
        trace.addStep(2, null);
        trace.addStep(3, usage(null, 7, null));
        trace.addStep(4, usage(1, null, 2));

        assertThat(trace.getInputTokens()).isEqualTo(11);
        assertThat(trace.getOutputTokens()).isEqualTo(12);
        assertThat(trace.getTotalTokens()).isEqualTo(17);
    }

    @Test
    void 完成态记录答案与终止原因() {
        AgentTrace trace = new AgentTrace("q");
        trace.complete("答案");
        assertThat(trace.getTerminationReason()).isEqualTo(AgentTrace.TerminationReason.COMPLETED);
        assertThat(trace.getFinalAnswer()).isEqualTo("答案");
        assertThat(trace.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void 步数打满与模型故障是不同终止态() {
        AgentTrace maxSteps = new AgentTrace("q");
        maxSteps.maxStepsReached("强制答案");
        assertThat(maxSteps.getTerminationReason()).isEqualTo(AgentTrace.TerminationReason.MAX_STEPS_REACHED);

        AgentTrace failed = new AgentTrace("q");
        failed.llmFailed("[模型调用失败] boom");
        assertThat(failed.getTerminationReason()).isEqualTo(AgentTrace.TerminationReason.LLM_FAILURE);
        assertThat(failed.getFinalAnswer()).contains("boom");
    }

    @Test
    void prompt版本默认unknown可显式设置() {
        AgentTrace trace = new AgentTrace("q");
        assertThat(trace.getPromptVersion()).isEqualTo("unknown");
        trace.setPromptVersion("v0.2.1");
        assertThat(trace.getPromptVersion()).isEqualTo("v0.2.1");
    }

    @Test
    void 工具调用记录成功与失败标记() {
        AgentTrace trace = new AgentTrace("q");
        AgentStep step = trace.addStep(1, null);
        step.addToolCall("search_knowledge", "{\"query\":\"x\"}", "【检索结果】…", true);
        step.addToolCall("get_document_detail", "{}", "[工具执行失败] …", false);
        assertThat(step.getToolCalls()).hasSize(2);
        assertThat(step.getToolCalls().get(0).isSuccess()).isTrue();
        assertThat(step.getToolCalls().get(1).isSuccess()).isFalse();
    }
}
