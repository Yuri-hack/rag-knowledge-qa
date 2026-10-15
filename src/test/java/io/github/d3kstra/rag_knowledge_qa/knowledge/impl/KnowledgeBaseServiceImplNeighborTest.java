package io.github.d3kstra.rag_knowledge_qa.knowledge.impl;

import io.github.d3kstra.rag_knowledge_qa.dto.internal.KnowledgeSearchResult;
import io.github.d3kstra.rag_knowledge_qa.document.FileProcessorFactory;
import io.github.d3kstra.rag_knowledge_qa.embed.EmbeddingService;
import io.github.d3kstra.rag_knowledge_qa.entity.DocumentChunk;
import io.github.d3kstra.rag_knowledge_qa.rerank.RerankerService;
import io.github.d3kstra.rag_knowledge_qa.repository.DocumentChunkRepository;
import io.github.d3kstra.rag_knowledge_qa.repository.UploadedDocumentRepository;
import io.github.d3kstra.rag_knowledge_qa.splitter.TextSplitter;
import io.github.d3kstra.rag_knowledge_qa.vector.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 邻域扩展单测（检索单元不变，只扩上下文）。
 * 背景：语料 chunk 极碎（45% < 80 字），事实可能落在相邻块；检索精度已到顶，
 * 缺的是单块信息自足性。见 KnowledgeBaseServiceImpl#expandNeighbors 注释。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceImplNeighborTest {

    @Mock private FileProcessorFactory fileProcessorFactory;
    @Mock private TextSplitter textSplitter;
    @Mock private EmbeddingService embeddingService;
    @Mock private VectorStoreService vectorStoreService;
    @Mock private DocumentChunkRepository chunkRepository;
    @Mock private UploadedDocumentRepository documentRepository;
    @Mock private RerankerService rerankerService;

    @InjectMocks private KnowledgeBaseServiceImpl service;

    private static KnowledgeSearchResult hit(long chunkId, String fileName, int chunkIndex) {
        KnowledgeSearchResult r = new KnowledgeSearchResult();
        r.setChunkId(chunkId);
        r.setDocumentId("DOC_" + fileName);
        r.setFileName(fileName);
        r.setChunkIndex(chunkIndex);
        r.setContent("命中内容" + chunkIndex);
        return r;
    }

    private static DocumentChunk chunk(long id, String fileName, int chunkIndex) {
        DocumentChunk c = new DocumentChunk();
        c.setId(id);
        c.setDocumentId("DOC_" + fileName);
        c.setFileName(fileName);
        c.setChunkIndex(chunkIndex);
        c.setContent("邻块内容" + chunkIndex);
        return c;
    }

    private void configure(int window, int maxTotal, int topHits) {
        ReflectionTestUtils.setField(service, "neighborWindow", window);
        ReflectionTestUtils.setField(service, "neighborMaxTotal", maxTotal);
        ReflectionTestUtils.setField(service, "neighborTopHits", topHits);
    }

    @Test
    @DisplayName("window=0：完全无操作，不查库、不加块、不打标记")
    void windowZeroIsNoOp() {
        configure(0, 30, 5);
        List<KnowledgeSearchResult> hits = List.of(hit(1, "F", 5), hit(2, "F", 9));

        List<KnowledgeSearchResult> out = service.expandNeighbors(hits);

        assertThat(out).hasSize(2);
        assertThat(out).allSatisfy(r -> assertThat(r.getNeighbor()).isNull());
        verifyNoInteractions(chunkRepository);
    }

    @Test
    @DisplayName("window=1：命中块的 ±1 邻块被加入、标记 neighbor=true，且紧跟命中块之后")
    void windowOneAddsNeighbors() {
        configure(1, 30, 5);
        List<KnowledgeSearchResult> hits = List.of(hit(1, "F", 5));
        when(chunkRepository.findByFileNameAndChunkIndexIn(eq("F"), any()))
                .thenReturn(List.of(chunk(20, "F", 6), chunk(10, "F", 4)));   // 故意乱序，验证按 chunkIndex 排序

        List<KnowledgeSearchResult> out = service.expandNeighbors(hits);

        assertThat(out).hasSize(3);
        assertThat(out.get(0).getChunkId()).isEqualTo(1L);          // 命中块保持第一
        assertThat(out.get(1).getChunkIndex()).isEqualTo(4);        // 前邻块
        assertThat(out.get(2).getChunkIndex()).isEqualTo(6);        // 后邻块
        assertThat(out.get(1).getNeighbor()).isTrue();
        assertThat(out.get(2).getNeighbor()).isTrue();
    }

    @Test
    @DisplayName("去重：邻块本身已是命中块时不重复添加")
    void dedupExistingHit() {
        configure(1, 30, 5);
        List<KnowledgeSearchResult> hits = List.of(hit(1, "F", 5), hit(10, "F", 4));
        when(chunkRepository.findByFileNameAndChunkIndexIn(eq("F"), any()))
                .thenReturn(List.of(chunk(10, "F", 4), chunk(20, "F", 6)));

        List<KnowledgeSearchResult> out = service.expandNeighbors(hits);

        // 命中 2 条 + 仅补入 chunkIndex=6 一条
        assertThat(out).hasSize(3);
        assertThat(out).filteredOn(r -> Boolean.TRUE.equals(r.getNeighbor()))
                .extracting(KnowledgeSearchResult::getChunkIndex).containsExactly(6);
    }

    @Test
    @DisplayName("上限：neighbor-max-total 生效，扩展后总数不超过上限")
    void maxTotalTruncates() {
        configure(1, 2, 5);
        List<KnowledgeSearchResult> hits = List.of(hit(1, "F", 5));
        when(chunkRepository.findByFileNameAndChunkIndexIn(eq("F"), any()))
                .thenReturn(List.of(chunk(10, "F", 4), chunk(20, "F", 6)));

        List<KnowledgeSearchResult> out = service.expandNeighbors(hits);

        assertThat(out).hasSize(2);   // 只允许再补 1 条
        assertThat(out.get(1).getChunkIndex()).isEqualTo(4);
    }

    @Test
    @DisplayName("只扩展排名前 top-hits 的命中块")
    void onlyTopHitsExpanded() {
        configure(1, 30, 1);
        List<KnowledgeSearchResult> hits = List.of(hit(1, "F", 5), hit(2, "G", 3));
        when(chunkRepository.findByFileNameAndChunkIndexIn(eq("F"), any()))
                .thenReturn(List.of(chunk(10, "F", 4)));

        List<KnowledgeSearchResult> out = service.expandNeighbors(hits);

        assertThat(out).hasSize(3);
        verify(chunkRepository, times(1)).findByFileNameAndChunkIndexIn(eq("F"), any());
    }
}
