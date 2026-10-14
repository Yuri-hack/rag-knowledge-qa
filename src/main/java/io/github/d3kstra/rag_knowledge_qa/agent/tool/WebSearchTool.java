package io.github.d3kstra.rag_knowledge_qa.agent.tool;

import io.github.d3kstra.rag_knowledge_qa.agent.Tool;
import io.github.d3kstra.rag_knowledge_qa.agent.TongYiAgentService;

import com.alibaba.dashscope.utils.JsonUtils;
import com.google.gson.JsonObject;
import io.github.d3kstra.rag_knowledge_qa.dto.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 联网搜索工具：内部调一次开 enableSearch 的单发 LLM 调用（不带 tools，规避与 tool_calls 的同请求互斥）。
 * 让模型自主决定"内部事实走 search_knowledge、公开事实走 web_search"。
 */
@Component
@RequiredArgsConstructor
public class WebSearchTool implements Tool {

    public static final String NAME = "web_search";

    private static final JsonObject PARAMETERS_SCHEMA = JsonUtils.parseString(
            "{\"type\":\"object\",\"properties\":{"
            + "\"query\":{\"type\":\"string\",\"description\":\"自包含的搜索关键词，如：2026年10月 北京 天气\"}"
            + "},\"required\":[\"query\"]}").getAsJsonObject();

    private final TongYiAgentService agentService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "搜索互联网公开信息（新闻、时事、天气、股价、公开数据等），返回基于实时搜索结果的简要回答。"
                + "仅用于公司外部的公开事实问题；内部制度、福利、流程类问题一律使用 search_knowledge，"
                + "不要用本工具代替知识库检索。仅当 search_knowledge 明确没有、且问题属于公开事实时才使用。";
    }

    @Override
    public JsonObject parametersSchema() {
        return PARAMETERS_SCHEMA;
    }

    @Override
    public String execute(JsonObject arguments) throws Exception {
        String query = arguments.get("query").getAsString();
        ChatResponse response = agentService.searchWeb(query);
        if (!response.isSuccess()) {
            throw new IllegalStateException("联网搜索调用失败: " + response.getErrorMessage());
        }
        return "【联网搜索结果】\n" + response.getContent();
    }
}
