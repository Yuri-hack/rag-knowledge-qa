package io.github.d3kstra.rag_knowledge_qa.agent;

import io.github.d3kstra.rag_knowledge_qa.agent.model.AgentTrace;
import io.github.d3kstra.rag_knowledge_qa.agent.tool.KnowledgeSearchTool;
import io.github.d3kstra.rag_knowledge_qa.agent.tool.WebSearchTool;

import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.alibaba.dashscope.utils.JsonUtils;
import com.google.gson.JsonObject;
import io.github.d3kstra.rag_knowledge_qa.dto.response.UsageInfo;
import io.github.d3kstra.rag_knowledge_qa.service.base.AskResult;
import io.github.d3kstra.rag_knowledge_qa.service.base.BaseTongYiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 循环边界：终止条件、预算强制、失败不伪装成正常终止。 */
class AgentLoopTest {

    private TongYiAgentService agentService;
    private AtomicInteger searchCalls;
    private AtomicInteger webCalls;
    private AgentLoop agentLoop;

    @BeforeEach
    void setUp() {
        agentService = mock(TongYiAgentService.class);
        searchCalls = new AtomicInteger();
        webCalls = new AtomicInteger();
        agentLoop = new AgentLoop(agentService, new ToolRegistry(List.of(searchStub(), webStub())));
    }

    private Tool searchStub() {
        return new Tool() {
            @Override
            public String name() {
                return KnowledgeSearchTool.NAME;
            }

            @Override
            public String description() {
                return "stub";
            }

            @Override
            public JsonObject parametersSchema() {
                return JsonUtils.parseString("{\"type\":\"object\",\"properties\":{"
                        + "\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}").getAsJsonObject();
            }

            @Override
            public String execute(JsonObject arguments) {
                searchCalls.incrementAndGet();
                return "【检索结果】stub";
            }
        };
    }

    private Tool webStub() {
        return new Tool() {
            @Override
            public String name() {
                return WebSearchTool.NAME;
            }

            @Override
            public String description() {
                return "stub";
            }

            @Override
            public JsonObject parametersSchema() {
                return JsonUtils.parseString("{\"type\":\"object\",\"properties\":{"
                        + "\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}").getAsJsonObject();
            }

            @Override
            public String execute(JsonObject arguments) {
                webCalls.incrementAndGet();
                return "【联网搜索结果】stub";
            }
        };
    }

    private static AskResult answer(String content) {
        return new AskResult(
                Message.builder().role(Role.ASSISTANT.getValue()).content(content).build(),
                UsageInfo.builder().inputTokens(1).outputTokens(1).totalTokens(2).build());
    }

    private static AskResult searchToolCall() {
        return toolCall(KnowledgeSearchTool.NAME, "{\"query\":\"P5 年假\"}");
    }

    private static AskResult webToolCall() {
        return toolCall(WebSearchTool.NAME, "{\"query\":\"北京 天气\"}");
    }

    private static AskResult toolCall(String name, String arguments) {
        ToolCallFunction toolCall = new ToolCallFunction();
        toolCall.setId("call_1");
        toolCall.setType("function");
        ToolCallFunction.CallFunction function = toolCall.new CallFunction();
        function.setName(name);
        function.setArguments(arguments);
        toolCall.setFunction(function);
        return new AskResult(
                Message.builder().role(Role.ASSISTANT.getValue()).toolCalls(List.of(toolCall)).build(),
                UsageInfo.builder().totalTokens(2).build());
    }

    @Test
    void 无工具调用即正常终止() {
        when(agentService.ask(anyList(), anyList())).thenReturn(answer("最终答案"));

        AgentTrace trace = agentLoop.run("q", AgentPrompts.SYSTEM_V02, 6);

        assertThat(trace.getTerminationReason()).isEqualTo(AgentTrace.TerminationReason.COMPLETED);
        assertThat(trace.getFinalAnswer()).isEqualTo("最终答案");
        assertThat(trace.getPromptVersion()).isEqualTo("v0.2.1");
        assertThat(searchCalls).hasValue(0);
    }

    @Test
    void 模型故障终止而不是继续循环() {
        when(agentService.ask(anyList(), anyList()))
                .thenReturn(answer(BaseTongYiService.LLM_FAILURE_MARKER + "IOException"));

        AgentTrace trace = agentLoop.run("q", AgentPrompts.SYSTEM_V02, 6);

        assertThat(trace.getTerminationReason()).isEqualTo(AgentTrace.TerminationReason.LLM_FAILURE);
        assertThat(trace.getFinalAnswer()).contains("IOException");
        verify(agentService, times(1)).ask(anyList(), anyList());
    }

    @Test
    void 检索预算按次强制超出后不再执行工具() {
        when(agentService.ask(anyList(), anyList()))
                .thenReturn(searchToolCall())          // 1..6 步都请求检索
                .thenReturn(searchToolCall())
                .thenReturn(searchToolCall())
                .thenReturn(searchToolCall())
                .thenReturn(searchToolCall())
                .thenReturn(searchToolCall())
                .thenReturn(answer("强制收尾答案"));    // 步数打满后的强制作答

        AgentTrace trace = agentLoop.run("q", AgentPrompts.SYSTEM_V02, 6);

        assertThat(searchCalls).hasValue(AgentPrompts.SEARCH_BUDGET);
        assertThat(trace.getTerminationReason()).isEqualTo(AgentTrace.TerminationReason.MAX_STEPS_REACHED);
        assertThat(trace.getFinalAnswer()).isEqualTo("强制收尾答案");
        long failedCalls = trace.getSteps().stream()
                .flatMap(step -> step.getToolCalls().stream())
                .filter(record -> !record.isSuccess())
                .count();
        assertThat(failedCalls).isEqualTo(6 - AgentPrompts.SEARCH_BUDGET);
    }

    @Test
    void 联网搜索预算按次强制超出后不再执行工具() {
        when(agentService.ask(anyList(), anyList()))
                .thenReturn(webToolCall())
                .thenReturn(webToolCall())
                .thenReturn(webToolCall())
                .thenReturn(webToolCall())
                .thenReturn(webToolCall())
                .thenReturn(webToolCall())
                .thenReturn(answer("强制收尾答案"));

        AgentTrace trace = agentLoop.run("q", AgentPrompts.SYSTEM_V04, 6);

        assertThat(webCalls).hasValue(AgentPrompts.WEB_SEARCH_BUDGET);
        assertThat(searchCalls).hasValue(0);
        assertThat(trace.getTerminationReason()).isEqualTo(AgentTrace.TerminationReason.MAX_STEPS_REACHED);
        long failedCalls = trace.getSteps().stream()
                .flatMap(step -> step.getToolCalls().stream())
                .filter(record -> !record.isSuccess())
                .count();
        assertThat(failedCalls).isEqualTo(6 - AgentPrompts.WEB_SEARCH_BUDGET);
        assertThat(trace.getPromptVersion()).isEqualTo("v0.4");
    }

    @Test
    void 步数打满后强制作答不再携带工具() {
        when(agentService.ask(anyList(), anyList()))
                .thenReturn(searchToolCall())
                .thenReturn(answer("强制作答"));

        agentLoop.run("q", AgentPrompts.SYSTEM_V02, 1);

        verify(agentService).ask(anyList(), org.mockito.ArgumentMatchers.eq(List.<ToolBase>of()));
    }

    @Test
    void maxSteps受硬上限约束() {
        // 始终请求工具：循环只能靠硬上限退出（10 步 + 1 次强制作答 = 11 次调用，而非 999）
        when(agentService.ask(anyList(), anyList())).thenReturn(searchToolCall());

        AgentTrace trace = agentLoop.run("q", AgentPrompts.SYSTEM_V02, 999);

        verify(agentService, times(11)).ask(anyList(), anyList());
        assertThat(trace.getTerminationReason()).isEqualTo(AgentTrace.TerminationReason.MAX_STEPS_REACHED);
    }

    @Test
    void 单发臂的模型故障不记成完成态() {
        when(agentService.ask(anyList(), anyList()))
                .thenReturn(answer(BaseTongYiService.LLM_FAILURE_MARKER + "boom"));

        AgentTrace trace = agentLoop.runSingleShot("q", AgentPrompts.SYSTEM_V02);

        assertThat(trace.getTerminationReason()).isEqualTo(AgentTrace.TerminationReason.LLM_FAILURE);
    }

    @Test
    void 单发臂正常完成并记录一次检索() {
        when(agentService.ask(anyList(), anyList())).thenReturn(answer("B 臂答案"));

        AgentTrace trace = agentLoop.runSingleShot("q", AgentPrompts.SYSTEM_V02);

        assertThat(trace.getTerminationReason()).isEqualTo(AgentTrace.TerminationReason.COMPLETED);
        assertThat(searchCalls).hasValue(1);
        assertThat(trace.getSteps()).hasSize(2);
    }
}
