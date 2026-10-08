package io.github.yuri_hack.rag_knowledge_qa.agent;

import com.alibaba.dashscope.utils.JsonUtils;
import com.google.gson.JsonObject;
import io.github.yuri_hack.rag_knowledge_qa.dto.internal.KnowledgeSearchResult;
import io.github.yuri_hack.rag_knowledge_qa.dto.request.SearchRequest;
import io.github.yuri_hack.rag_knowledge_qa.knowledge.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把现有检索链路包成 agent 工具：
 * searchKnowledge 的 embedding → Milvus → MySQL 补元数据 → rerank → cutOff 全部复用，零改动。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeSearchTool implements Tool {

    /** 工具结果截断：agent 特有的上下文问题是循环里工具结果堆积（5步×5条就炸），不是聊天历史 */
    private static final int MAX_CHUNK_CHARS = 400;

    /** agent 循环内省上下文用 topK=5（基线流水线是 10）；差异已记录在 prompt-changelog，D3 对齐口径 */
    private static final int TOP_K = 5;

    private final KnowledgeBaseService knowledgeBaseService;

    @Override
    public String name() {
        return "search_knowledge";
    }

    @Override
    public String description() {
        return "检索公司内部制度知识库，返回相关制度条款片段。"
                + "query 必须是自包含的检索关键词（多轮对话时先消解指代）。"
                + "可多次调用：结果与问题冲突、或不含所需信息时，应加限定词（如年份、适用主体、部门）后重试。";
    }

    @Override
    public JsonObject parametersSchema() {
        return JsonUtils.parseString("{\"type\":\"object\",\"properties\":{"
                + "\"query\":{\"type\":\"string\",\"description\":\"自包含的检索关键词，如：P5 年假 天数\"}"
                + "},\"required\":[\"query\"]}").getAsJsonObject();
    }

    @Override
    public String execute(JsonObject arguments) {
        String query = arguments.get("query").getAsString();
        SearchRequest request = new SearchRequest();
        request.setQuery(query);
        request.setTopK(TOP_K);

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
            String content = r.getContent() == null ? "" : r.getContent();
            if (content.length() > MAX_CHUNK_CHARS) {
                content = content.substring(0, MAX_CHUNK_CHARS) + "…(截断)";
            }
            sb.append(content).append('\n');
        }
        return sb.toString();
    }
}
