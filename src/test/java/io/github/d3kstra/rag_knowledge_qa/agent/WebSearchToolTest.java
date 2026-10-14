package io.github.d3kstra.rag_knowledge_qa.agent;

import com.google.gson.JsonObject;
import io.github.d3kstra.rag_knowledge_qa.dto.response.ChatResponse;
import io.github.d3kstra.rag_knowledge_qa.service.base.AskResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** WebSearchTool：成功包装、失败抛出（由 ToolRegistry 转失败文本回传循环）。 */
class WebSearchToolTest {

    private final TongYiAgentService agentService = mock(TongYiAgentService.class);
    private final WebSearchTool tool = new WebSearchTool(agentService);

    private static JsonObject args(String query) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("query", query);
        return arguments;
    }

    @Test
    void 成功时返回联网搜索结果包装() throws Exception {
        when(agentService.searchWeb("北京 天气")).thenReturn(
                ChatResponse.builder().success(true).content("晴，18℃").build());

        String result = tool.execute(args("北京 天气"));

        assertThat(result).startsWith("【联网搜索结果】").contains("晴，18℃");
        verify(agentService).searchWeb("北京 天气");
    }

    @Test
    void 搜索调用失败时抛出由注册表转失败文本() {
        when(agentService.searchWeb(any())).thenReturn(
                ChatResponse.builder().success(false).errorMessage("服务调用失败: timeout").build());

        assertThatThrownBy(() -> tool.execute(args("q")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("联网搜索调用失败");
    }

    @Test
    void 声明query必填参数() {
        assertThat(tool.parametersSchema().getAsJsonArray("required"))
                .anySatisfy(e -> assertThat(e.getAsString()).isEqualTo("query"));
        assertThat(tool.name()).isEqualTo("web_search");
    }
}
