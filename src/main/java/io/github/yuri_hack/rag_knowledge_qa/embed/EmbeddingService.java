package io.github.yuri_hack.rag_knowledge_qa.embed;

import io.github.yuri_hack.rag_knowledge_qa.exception.VectorException;

public interface EmbeddingService {
    /**
     * 将文本转换为向量
     */
    float[] getEmbedding(String text) throws VectorException;

    /**
     * 计算向量相似度
     */
    double calculateSimilarity(float[] vector1, float[] vector2);
}