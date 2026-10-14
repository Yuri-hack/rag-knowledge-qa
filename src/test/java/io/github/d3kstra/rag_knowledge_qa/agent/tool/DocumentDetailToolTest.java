package io.github.d3kstra.rag_knowledge_qa.agent.tool;

import com.google.gson.JsonObject;
import io.github.d3kstra.rag_knowledge_qa.entity.DocumentChunk;
import io.github.d3kstra.rag_knowledge_qa.repository.DocumentChunkRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 长文档分窗翻页：part 参数、超界与未找到提示。 */
class DocumentDetailToolTest {

    private static final String DOC_ID = "DOC_TEST";
    private static final int CHUNK_CHARS = 1000;
    private static final int CHUNK_COUNT = 3;

    private static DocumentDetailTool toolWith(int chunkCount) {
        DocumentChunkRepository repository = mock(DocumentChunkRepository.class);
        List<DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocumentId(DOC_ID);
            chunk.setFileName("测试长文档");
            chunk.setChunkIndex(i);
            chunk.setContent("内容" + i + "x".repeat(CHUNK_CHARS));
            chunks.add(chunk);
        }
        // 仅对已知 documentId 命中；未知 id 走 Mockito 默认空集合，用于验证未找到分支
        when(repository.findByDocumentIdOrderByChunkIndexAsc(DOC_ID)).thenReturn(chunks);
        return new DocumentDetailTool(repository);
    }

    private static JsonObject args(String documentId, Integer part) {
        JsonObject json = new JsonObject();
        json.addProperty("documentId", documentId);
        if (part != null) {
            json.addProperty("part", part);
        }
        return json;
    }

    @Test
    void 首窗返回开头内容并提示下一页() {
        String result = toolWith(CHUNK_COUNT).execute(args(DOC_ID, null));
        assertThat(result).contains("part 1").contains("块0").contains("part=2");
    }

    @Test
    void 第二窗返回后续内容() {
        int totalChars = CHUNK_CHARS * CHUNK_COUNT;
        int maxPart = (totalChars + 2399) / 2400;
        assertThat(maxPart).isEqualTo(2);

        String result = toolWith(CHUNK_COUNT).execute(args(DOC_ID, 2));
        assertThat(result).contains("part 2").contains("块2").contains("文档末尾");
        assertThat(result).doesNotContain("part=3");
    }

    @Test
    void 超界页码给出明确提示而不是空内容() {
        String result = toolWith(CHUNK_COUNT).execute(args(DOC_ID, 9));
        assertThat(result).startsWith("[超出范围]");
    }

    @Test
    void 非法页码兜底为第一窗() {
        String result = toolWith(CHUNK_COUNT).execute(args(DOC_ID, 0));
        assertThat(result).contains("part 1");
    }

    @Test
    void 未找到文档返回可修正提示() {
        String result = toolWith(CHUNK_COUNT).execute(args("DOC_UNKNOWN", 1));
        assertThat(result).startsWith("[未找到文档]").contains("DOC_UNKNOWN");
    }

    @Test
    void 短文档单窗且标记末尾() {
        String result = toolWith(1).execute(args(DOC_ID, 1));
        assertThat(result).contains("已到文档末尾").doesNotContain("part=2");
    }
}
