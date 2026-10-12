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

    /**
     * 检索配置与基线完全同构（2026-10-12 终态）：topK=10 + topRatio=0.5 —— 同召回宽度、
     * 同 rerank 竞争池（10 选 5）、同最终 5 条。此前经历两版错误口径（未设 ratio→实得 3 条；
     * 误设 ratio=1.0→只回 1 条，R03 badcase），见 prompt-changelog 三次订正。
     * agent 与基线的单次检索自此等价，架构对比的剩余变量只有循环本身。
     */
    private static final int TOP_K = 10;
    private static final double TOP_RATIO = 0.5;

    // 内容截断已移除（2026-10-12）：512 字符 chunk 截到 400 仅省 ~6-10% token，
    // 却系统性砍掉 chunk 尾部的"文字版"数字行，制造基线不存在的失败面；
    // 上下文控制的大杠杆是条数（topRatio）与搜索预算（AgentLoop 计数器）。
    // 语料扩大/chunk 加长时再引入"按句边界截断 + offset 分页"（配合 DocumentDetailTool）。

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
        request.setTopRatio(TOP_RATIO);

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
