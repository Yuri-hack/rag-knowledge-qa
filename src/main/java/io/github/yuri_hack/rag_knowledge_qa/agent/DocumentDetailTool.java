package io.github.yuri_hack.rag_knowledge_qa.agent;

import com.alibaba.dashscope.utils.JsonUtils;
import com.google.gson.JsonObject;
import io.github.yuri_hack.rag_knowledge_qa.entity.DocumentChunk;
import io.github.yuri_hack.rag_knowledge_qa.repository.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按 documentId 拉取某篇制度的完整内容（全部分块按序拼接）。
 * 存在的理由：C01 类失败——检索命中的片段缺少所需数字/身份标识时，
 * 单靠再次 search 无法保证命中，按文档拉全文是确定性修复路径。
 */
@Component
@RequiredArgsConstructor
public class DocumentDetailTool implements Tool {

    /** 总量截断：长文档（如整本书 287 chunk）不能全量进 context */
    private static final int MAX_TOTAL_CHARS = 2400;

    private final DocumentChunkRepository chunkRepository;

    @Override
    public String name() {
        return "get_document_detail";
    }

    @Override
    public String description() {
        return "按 documentId 获取某篇制度的完整内容（全部分块按序拼接）。"
                + "当 search_knowledge 返回的片段缺少所需数字、或需要确认该文档的适用范围/年份/完整条款时使用。"
                + "documentId 见 search_knowledge 结果中每条前面的标注。";
    }

    @Override
    public JsonObject parametersSchema() {
        return JsonUtils.parseString("{\"type\":\"object\",\"properties\":{"
                + "\"documentId\":{\"type\":\"string\",\"description\":\"文档ID，来自 search_knowledge 结果\"}"
                + "},\"required\":[\"documentId\"]}").getAsJsonObject();
    }

    @Override
    public String execute(JsonObject arguments) {
        String documentId = arguments.get("documentId").getAsString();
        List<DocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId);
        if (chunks == null || chunks.isEmpty()) {
            return "[未找到文档] documentId=" + documentId + "（确认 ID 是否来自 search_knowledge 结果）";
        }
        StringBuilder sb = new StringBuilder("【文档全文】《")
                .append(chunks.get(0).getFileName()).append("》 共 ")
                .append(chunks.size()).append(" 个分块：\n");
        int used = 0;
        for (DocumentChunk c : chunks) {
            String content = c.getContent() == null ? "" : c.getContent();
            sb.append("\n[块").append(c.getChunkIndex()).append("] ").append(content).append('\n');
            used += content.length();
            if (used >= MAX_TOTAL_CHARS) {
                sb.append("…(内容过长已截断，如需后续部分请说明)"); // 长文档截断（如整本书场景）
                break;
            }
        }
        return sb.toString();
    }
}
