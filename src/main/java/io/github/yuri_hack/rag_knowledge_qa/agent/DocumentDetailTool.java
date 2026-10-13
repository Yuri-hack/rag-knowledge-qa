package io.github.yuri_hack.rag_knowledge_qa.agent;

import com.alibaba.dashscope.utils.JsonUtils;
import com.google.gson.JsonObject;
import io.github.yuri_hack.rag_knowledge_qa.entity.DocumentChunk;
import io.github.yuri_hack.rag_knowledge_qa.repository.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 按 documentId 拉全文：检索片段缺数字时的确定性修复路径。 */
@Component
@RequiredArgsConstructor
public class DocumentDetailTool implements Tool {

    /** 单窗字符数 */
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
                + "documentId 见 search_knowledge 结果中每条前面的标注。"
                + "长文档按 2400 字符分窗返回：part=1（默认）从头开始，内容末尾提示继续时传 part=2、3…直到提示已到文档末尾。";
    }

    @Override
    public JsonObject parametersSchema() {
        return JsonUtils.parseString("{\"type\":\"object\",\"properties\":{"
                + "\"documentId\":{\"type\":\"string\",\"description\":\"文档ID，来自 search_knowledge 结果\"},"
                + "\"part\":{\"type\":\"integer\",\"description\":\"长文档的分窗页码，从 1 开始；短文档只需 part=1（可省略）\"}"
                + "},\"required\":[\"documentId\"]}").getAsJsonObject();
    }

    @Override
    public String execute(JsonObject arguments) {
        String documentId = arguments.get("documentId").getAsString();
        // part 缺省 1，非法值兜底为 1
        int part = 1;
        try {
            if (arguments.has("part") && !arguments.get("part").isJsonNull()) {
                part = Math.max(1, arguments.get("part").getAsInt());
            }
        } catch (Exception ignored) {
        }
        List<DocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId);
        if (chunks == null || chunks.isEmpty()) {
            return "[未找到文档] documentId=" + documentId + "（确认 ID 是否来自 search_knowledge 结果）";
        }
        int window = Math.max(1, part);
        int used = 0;
        StringBuilder sb = new StringBuilder("【文档内容 part ").append(window).append("/】《")
                .append(chunks.get(0).getFileName()).append("》 共 ")
                .append(chunks.size()).append(" 个分块：\n");
        int total = 0;
        for (DocumentChunk c : chunks) {
            total += (c.getContent() == null ? 0 : c.getContent().length());
        }
        int maxPart = (total + MAX_TOTAL_CHARS - 1) / MAX_TOTAL_CHARS;
        if (window > maxPart) {
            return "[超出范围] 该文档共 " + maxPart + " 个部分，part=" + window + " 不存在。";
        }
        int skipped = 0;
        boolean truncatedTail = false;
        for (DocumentChunk c : chunks) {
            String content = c.getContent() == null ? "" : c.getContent();
            if (skipped + content.length() <= (window - 1) * MAX_TOTAL_CHARS) {
                skipped += content.length(); // 整块落在前窗，跳过
                continue;
            }
            sb.append("\n[块").append(c.getChunkIndex()).append("] ").append(content).append('\n');
            used += content.length();
            if (used >= MAX_TOTAL_CHARS) {
                truncatedTail = true;
                break;
            }
        }
        if (window < maxPart) {
            sb.append("…(本部分到此，如需后续内容请传 part=").append(window + 1).append(")");
        } else if (truncatedTail) {
            sb.append("(文档末尾)");
        } else {
            sb.append("(已到文档末尾)");
        }
        return sb.toString();
    }
}
