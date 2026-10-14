package io.github.d3kstra.rag_knowledge_qa.agent;

import com.alibaba.dashscope.utils.JsonUtils;
import com.google.gson.JsonObject;
import io.github.d3kstra.rag_knowledge_qa.dto.internal.KnowledgeSearchResult;
import io.github.d3kstra.rag_knowledge_qa.dto.request.SearchRequest;
import io.github.d3kstra.rag_knowledge_qa.knowledge.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/** 检索链路（embedding→Milvus→补元数据→rerank→cutOff）的 agent 工具封装，零改动复用。 */
@Component
@RequiredArgsConstructor
public class KnowledgeSearchTool implements Tool {

    /** 检索漏斗：与基线保持同构（默认池 20 → 留 10），保证架构对比只剩循环一个变量 */
    @Value("${rag.agent.search.top-k:20}")
    private int topK;

    @Value("${rag.agent.search.top-ratio:10}")
    private double topRatio;

    // 不做内容截断：曾截 400 字符砍掉尾部数字行，制造基线没有的失败面（已移除）。

    /** 工具名收敛为常量：AgentLoop 预算计数与 B 臂检索共用 */
    public static final String NAME = "search_knowledge";

    private static final JsonObject PARAMETERS_SCHEMA = JsonUtils.parseString(
            "{\"type\":\"object\",\"properties\":{"
            + "\"query\":{\"type\":\"string\",\"description\":\"自包含的检索关键词，如：P5 年假 天数\"}"
            + "},\"required\":[\"query\"]}").getAsJsonObject();

    private final KnowledgeBaseService knowledgeBaseService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "检索公司内部制度知识库，返回相关制度条款片段。"
                + "query 必须是自包含的检索关键词（多轮对话时先消解指代）。"
                + "可多次调用：结果与问题冲突、或不含所需信息时，应加限定词（如年份、适用主体、部门）后重试。";
    }

    @Override
    public JsonObject parametersSchema() {
        return PARAMETERS_SCHEMA;
    }

    @Override
    public String execute(JsonObject arguments) {
        String query = arguments.get("query").getAsString();
        SearchRequest request = new SearchRequest();
        request.setQuery(query);
        request.setTopK(topK);
        request.setTopRatio(topRatio);

        List<KnowledgeSearchResult> results = knowledgeBaseService.searchKnowledge(request);

        if (results == null || results.isEmpty()) {
            return "【检索结果】未找到与「" + query + "」相关的内容。";
        }

        StringBuilder sb = new StringBuilder("【检索结果】共 ").append(results.size()).append(" 条：\n");
        int i = 1;
        for (KnowledgeSearchResult r : results) {
            sb.append("【").append(i++).append("】《").append(r.getFileName())
                    .append("》 documentId=").append(r.getDocumentId())
                    .append(" chunk#").append(r.getChunkIndex())
                    .append(" 相似度=").append(String.format("%.2f", r.getSimilarity()))
                    .append('\n');
            sb.append(r.getContent() == null ? "" : r.getContent()).append('\n');
        }
        return sb.toString();
    }
}
