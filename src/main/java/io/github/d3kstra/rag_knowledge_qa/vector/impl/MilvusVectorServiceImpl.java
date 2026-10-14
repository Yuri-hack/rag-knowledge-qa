package io.github.d3kstra.rag_knowledge_qa.vector.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.d3kstra.rag_knowledge_qa.config.MilvusConfig;
import io.github.d3kstra.rag_knowledge_qa.dto.internal.VectorSearchResult;
import io.github.d3kstra.rag_knowledge_qa.embed.EmbeddingService;
import io.github.d3kstra.rag_knowledge_qa.entity.DocumentChunk;
import io.github.d3kstra.rag_knowledge_qa.exception.VectorStorageException;
import io.github.d3kstra.rag_knowledge_qa.vector.VectorStoreService;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.utility.request.FlushReq;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Milvus 2.5 向量存储实现（MilvusClientV2）。
 *
 * 与 2.3 版本的差异：
 *  1. 插入必须带 {@code text} 字段——BM25 Function 需要正文才能生成稀疏向量（当前 Milvus 不存正文，
 *     检索命中后仍由 MySQL 提供 content）；
 *  2. 新增服务端混合检索：稠密 + BM25 稀疏 + RRFRanker，应用侧不再做融合；
 *  3. 融合分数（RRF）放进 similarity 字段，语义上不再是余弦相似度。
 */
@Slf4j
@Service
public class MilvusVectorServiceImpl implements VectorStoreService {

    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;
    private final MilvusConfig milvusConfig;

    private static final String VECTOR_FIELD = "vector";
    private static final String DOCUMENT_ID_FIELD = "document_id";
    private static final String CHUNK_INDEX_FIELD = "chunk_index";
    private static final String CHUNK_ID_FIELD = "chunk_id";
    private static final String TEXT_FIELD = "text";
    private static final String SPARSE_FIELD = "sparse";

    /** RRF 平滑常数（Milvus 默认 60） */
    private static final int RRF_K = 60;

    private static final Gson GSON = new Gson();

    public MilvusVectorServiceImpl(MilvusClientV2 milvusClient, EmbeddingService embeddingService, MilvusConfig milvusConfig) {
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
        this.milvusConfig = milvusConfig;
    }

    @Override
    public List<Long> insertVectors(List<DocumentChunk> chunks) throws VectorStorageException {
        if (chunks == null || chunks.isEmpty()) {
            log.warn("插入向量数据为空");
            return Collections.emptyList();
        }

        try {
            List<JsonObject> rows = new ArrayList<>();
            List<Long> insertedIds = new ArrayList<>();

            for (DocumentChunk chunk : chunks) {
                float[] embedding = embeddingService.getEmbedding(chunk.getContent());
                if (embedding == null) {
                    log.warn("文档块 {} 的向量为空，跳过插入", chunk.getId());
                    continue;
                }

                JsonObject row = new JsonObject();
                row.addProperty(CHUNK_ID_FIELD, chunk.getId());
                row.add(VECTOR_FIELD, GSON.toJsonTree(toFloatList(embedding)));
                row.addProperty(DOCUMENT_ID_FIELD, chunk.getDocumentId());
                row.addProperty(CHUNK_INDEX_FIELD, chunk.getChunkIndex());
                // BM25 的输入：必须带正文，稀疏向量由服务端 Function 生成
                row.addProperty(TEXT_FIELD, chunk.getContent());
                rows.add(row);
                insertedIds.add(chunk.getId());
            }

            if (rows.isEmpty()) {
                log.warn("没有有效的向量数据可插入");
                return Collections.emptyList();
            }

            InsertResp response = milvusClient.insert(InsertReq.builder()
                    .collectionName(milvusConfig.getCollectionName())
                    .data(rows)
                    .build());

            // 显式 flush：插入只写 growing segment，未落盘的数据在 querynode 抖动/段回收时会丢
            //（2026-10-13 两次"神秘清库"均为此因：集合 ID 未变但 row_count=0、段列表为空）
            //
            // 批量入库（upload_corpus.py 全量重建）时可置 rag.vector.flush-on-insert=false：
            // 逐篇 flush 会让每个 growing segment 反复封存并触发 compaction，实测把入库速率压到
            // ~7 篇/分（Milvus 日志 60 秒内 191 条 flush 相关记录）。批量场景由调用方在收尾
            // 显式 flush 一次即可，耐久性不变。
            if (flushOnInsert) {
                flushCollection();
            }
            log.info("成功插入 {} 个向量到Milvus，服务端确认 {} 条", rows.size(), response.getInsertCnt());
            return insertedIds;

        } catch (Exception e) {
            log.error("插入向量到Milvus失败", e);
            throw new VectorStorageException("向量插入失败", e);
        }
    }

    /** 显式 flush：把 growing segment 落盘，避免未 flush 数据在段回收/节点抖动时丢失。 */
    /** 逐篇 flush 开关：批量全量重建时关闭以避开 segment/compaction churn */
    @Value("${rag.vector.flush-on-insert:true}")
    private boolean flushOnInsert;

    private void flushCollection() {
        try {
            milvusClient.flush(FlushReq.builder()
                    .collectionNames(List.of(milvusConfig.getCollectionName()))
                    .build());
        } catch (Exception e) {
            log.warn("Milvus flush 异常（不影响插入结果，但数据可能未落盘）", e);
        }
    }

    @Override
    public List<VectorSearchResult> searchSimilarVectors(float[] queryVector, int topK) throws VectorStorageException {
        try {
            SearchResp response = milvusClient.search(SearchReq.builder()
                    .collectionName(milvusConfig.getCollectionName())
                    .data(List.of(new FloatVec(toFloatList(queryVector))))
                    .annsField(VECTOR_FIELD)
                    .topK(topK)
                    .outputFields(outputFields())
                    .build());

            List<VectorSearchResult> results = toVectorSearchResults(response);
            log.debug("向量搜索完成，查询向量维度: {}, 返回结果数: {}", queryVector.length, results.size());
            return results;

        } catch (Exception e) {
            log.error("Milvus向量搜索失败，查询向量维度: {}, topK: {}", queryVector.length, topK, e);
            throw new VectorStorageException("向量搜索失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<VectorSearchResult> hybridSearch(float[] queryVector, String queryText, int topK) throws VectorStorageException {
        try {
            // 稠密路 + 稀疏路（EmbeddedText 交给服务端 BM25 analyzer），融合用 RRF
            HybridSearchReq request = HybridSearchReq.builder()
                    .collectionName(milvusConfig.getCollectionName())
                    .searchRequests(List.of(
                            AnnSearchReq.builder()
                                    .vectorFieldName(VECTOR_FIELD)
                                    .vectors(List.of(new FloatVec(toFloatList(queryVector))))
                                    .topK(topK)
                                    .params("{}")
                                    .build(),
                            AnnSearchReq.builder()
                                    .vectorFieldName(SPARSE_FIELD)
                                    .vectors(List.of(new EmbeddedText(queryText)))
                                    .topK(topK)
                                    .params("{}")
                                    .build()))
                    .ranker(new RRFRanker(RRF_K))
                    .topK(topK)
                    .outFields(outputFields())
                    .build();

            SearchResp response = milvusClient.hybridSearch(request);
            List<VectorSearchResult> results = toVectorSearchResults(response);
            log.debug("混合检索完成（dense+sparse+RRF），返回结果数: {}", results.size());
            return results;

        } catch (Exception e) {
            log.error("Milvus混合检索失败，topK: {}", topK, e);
            throw new VectorStorageException("混合检索失败: " + e.getMessage(), e);
        }
    }

    private static List<String> outputFields() {
        return List.of(DOCUMENT_ID_FIELD, CHUNK_INDEX_FIELD, CHUNK_ID_FIELD);
    }

    /**
     * 解析检索结果。dense 的分数是 IP（嵌入已归一化 ⇒ 等价余弦）；
     * hybrid 的分数是 RRF 融合分，两者都放进 similarity 字段。
     */
    private static List<VectorSearchResult> toVectorSearchResults(SearchResp response) {
        List<VectorSearchResult> results = new ArrayList<>();
        if (response == null || response.getSearchResults() == null) {
            return results;
        }
        for (List<SearchResp.SearchResult> hits : response.getSearchResults()) {
            for (SearchResp.SearchResult hit : hits) {
                try {
                    var entity = hit.getEntity();
                    String documentId = String.valueOf(entity.get(DOCUMENT_ID_FIELD));
                    int chunkIndex = ((Number) entity.get(CHUNK_INDEX_FIELD)).intValue();
                    // chunk_id 是主键，可能在 entity 里也可能只在顶层 id 上
                    Object chunkIdValue = entity.get(CHUNK_ID_FIELD) != null ? entity.get(CHUNK_ID_FIELD) : hit.getId();
                    long chunkId = ((Number) chunkIdValue).longValue();
                    double score = hit.getScore() == null ? 0.0 : hit.getScore().doubleValue();

                    results.add(VectorSearchResult.of(chunkIndex, score, documentId, chunkId));
                } catch (Exception fieldException) {
                    log.warn("处理搜索结果字段时出错，跳过该结果。Score: {}", hit.getScore(), fieldException);
                }
            }
        }
        return results;
    }

    private static List<Float> toFloatList(float[] vector) {
        return IntStream.range(0, vector.length)
                .mapToObj(i -> vector[i])
                .collect(Collectors.toList());
    }
}
