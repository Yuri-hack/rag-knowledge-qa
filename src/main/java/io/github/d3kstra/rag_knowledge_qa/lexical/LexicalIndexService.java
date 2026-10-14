package io.github.d3kstra.rag_knowledge_qa.lexical;

import java.util.List;

/**
 * 词法（BM25）检索：稠密向量的补充通道。
 *
 * 为什么需要：稠密向量对**无意义 token**（资产编号、单号、型号代码）几乎无法精确匹配——
 * 编号的嵌入与"包含大量编号的 chunk"近乎正交，导致"按编号找那一条"失败
 * （见 results/multihop-bridge-20261014.md 的 MH204 失败分析）。
 * 词法通道按 token 精确命中，df 极小 → IDF 极高 → 那一条会排到最前。
 */
public interface LexicalIndexService {

    /**
     * 词法命中：chunk 定位信息 + BM25 分数。
     *
     * @param exactMatch 是否命中查询中的**稀有 token**（长度≥6 的字母数字串且 df≤3，如资产编号/单号）。
     *                   这类查询里精确匹配是唯一可靠信号，调用方应据此把它排在语义 rerank 之前。
     */
    record LexicalHit(Long chunkId, String documentId, String fileName,
                      Integer chunkIndex, String content, double score, boolean exactMatch) {
    }

    /** 按 BM25 返回得分最高的 topK 条（分数降序） */
    List<LexicalHit> search(String query, int topK);

    /** 重建索引（上传完成后或 TTL 到期时调用） */
    void rebuild();

    /** 当前索引内的 chunk 数（用于健康检查与测试） */
    int indexedChunkCount();
}
