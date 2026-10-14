package io.github.d3kstra.rag_knowledge_qa.lexical.impl;

import io.github.d3kstra.rag_knowledge_qa.entity.DocumentChunk;
import io.github.d3kstra.rag_knowledge_qa.lexical.LexicalIndexService;
import io.github.d3kstra.rag_knowledge_qa.repository.DocumentChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 词法通道单测：分词正确性 + 精确编号命中 + 中文二元组召回。
 * 动机（results/multihop-bridge-20261014.md MH204）：稠密向量无法精确匹配资产编号，
 * 词法通道必须保证"按编号找那一条"稳定排第一。
 */
class Bm25LexicalIndexServiceTest {

    private DocumentChunkRepository repository;
    private Bm25LexicalIndexService service;

    @BeforeEach
    void setUp() {
        repository = mock(DocumentChunkRepository.class);
        service = new Bm25LexicalIndexService(repository);
        ReflectionTestUtils.setField(service, "indexTtlSeconds", 300L);
    }

    private static DocumentChunk chunk(long id, String content) {
        DocumentChunk c = new DocumentChunk();
        c.setId(id);
        c.setDocumentId("DOC_" + id);
        c.setFileName("N01-固定资产保修期限登记表");
        c.setChunkIndex((int) id);
        c.setContent(content);
        return c;
    }

    @Test
    @DisplayName("分词：资产编号整体成词，且分段入索引")
    void tokenizeKeepsAssetTagAndParts() {
        List<String> tokens = Bm25LexicalIndexService.tokenize("资产编号 YC-HY-2026-0003 的保修期");

        assertThat(tokens).contains("yc-hy-2026-0003");   // 整体成词 → 精确命中
        assertThat(tokens).contains("yc", "hy", "2026", "0003");  // 分段 → 模糊回忆也能召回
        assertThat(tokens).contains("资产", "产编", "保修");       // 中文二元组
    }

    @Test
    @DisplayName("分词：含 / 的型号整体成词")
    void tokenizeKeepsSlashModelCode() {
        assertThat(Bm25LexicalIndexService.tokenize("型号 MRX33CH/A 整机 36 个月"))
                .contains("mrx33ch/a");
    }

    @Test
    @DisplayName("检索：无意义资产编号能精确命中，且排在语义相近的干扰行之前")
    void exactAssetCodeRanksFirst() {
        List<DocumentChunk> chunks = new ArrayList<>();
        // 400 行同构登记表（模拟真实场景：语义几乎相同的干扰行）
        for (int i = 1; i <= 40; i++) {
            chunks.add(chunk(i, "YC-JJ-2025-%04d | 整机 36 个月 | 电池 12 个月 | 屏幕 24 个月".formatted(i)));
        }
        // 目标行藏在其中
        chunks.add(chunk(999, "YC-HY-2026-0003 | 整机 36 个月 | 电池 18 个月 | 屏幕 24 个月"));
        when(repository.findAll()).thenReturn(chunks);

        List<LexicalIndexService.LexicalHit> hits = service.search("YC-HY-2026-0003", 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).chunkId()).isEqualTo(999L);
        assertThat(hits.get(0).content()).contains("YC-HY-2026-0003");
    }

    @Test
    @DisplayName("检索：中文问题按二元组召回相关条款")
    void cjkQueryMatchesRelevantChunk() {
        when(repository.findAll()).thenReturn(List.of(
                chunk(1, "员工年假天数按职级与司龄确定，以《职级与假期对照表》为准。"),
                chunk(2, "差旅费报销需在出差结束后 10 个工作日内提交。"),
                chunk(3, "设备保修期自领用登记之日起算。")));

        List<LexicalIndexService.LexicalHit> hits = service.search("年假有多少天", 3);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).chunkId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("索引：重建后统计 chunk 数；空语料不抛异常")
    void rebuildAndCount() {
        when(repository.findAll()).thenReturn(List.of(chunk(1, "年假制度"), chunk(2, "报销制度")));
        service.rebuild();
        assertThat(service.indexedChunkCount()).isEqualTo(2);

        when(repository.findAll()).thenReturn(List.of());
        service.rebuild();
        assertThat(service.indexedChunkCount()).isZero();
        assertThat(service.search("年假", 5)).isEmpty();
    }

    @Test
    @DisplayName("检索：资产编号查询标记 exactMatch=true，普通中文查询为 false")
    void exactMatchFlagOnlyForRareTokens() {
        when(repository.findAll()).thenReturn(List.of(
                chunk(1, "YC-HY-2026-0003 | 整机 36 个月 | 电池 18 个月 | 屏幕 24 个月"),
                chunk(2, "员工年假天数按职级与司龄确定")));

        List<LexicalIndexService.LexicalHit> byCode = service.search("YC-HY-2026-0003", 5);
        assertThat(byCode).isNotEmpty();
        assertThat(byCode.get(0).exactMatch()).isTrue();

        List<LexicalIndexService.LexicalHit> byText = service.search("年假有多少天", 5);
        assertThat(byText).isNotEmpty();
        assertThat(byText.get(0).exactMatch()).isFalse();
    }

    @Test
    @DisplayName("检索：无关查询返回空，不误召")
    void unrelatedQueryReturnsNothing() {
        when(repository.findAll()).thenReturn(List.of(chunk(1, "员工年假天数按职级确定")));

        assertThat(service.search("zzzz-9999", 5)).isEmpty();
    }
}
