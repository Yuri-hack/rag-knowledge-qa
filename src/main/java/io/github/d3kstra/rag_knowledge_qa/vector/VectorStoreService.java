package io.github.d3kstra.rag_knowledge_qa.vector;

import io.github.d3kstra.rag_knowledge_qa.dto.internal.VectorSearchResult;
import io.github.d3kstra.rag_knowledge_qa.entity.DocumentChunk;

import java.util.List;

public interface VectorStoreService {

    List<Long> insertVectors(List<DocumentChunk> chunks);

    /**
     * 纯稠密检索（余弦/IP 相似度）。
     * 供 getMaxSimilarity 等只关心"最相似度"的场景使用。
     */
    List<VectorSearchResult> searchSimilarVectors(float[] queryVector, int topK);

    /**
     * 服务端原生混合检索（Milvus 2.5）：稠密 + BM25 稀疏 + RRF 融合。
     *
     * @param queryText 原始查询文本，稀疏（全文）通道用它做 BM25 检索
     * @param topK      每路召回数，同时也是融合后返回数
     * @return 结果为 RRF 融合分数（不是余弦相似度），放在 similarity 字段
     */
    List<VectorSearchResult> hybridSearch(float[] queryVector, String queryText, int topK);
}
