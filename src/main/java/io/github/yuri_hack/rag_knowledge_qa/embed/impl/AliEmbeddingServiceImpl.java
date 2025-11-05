package io.github.yuri_hack.rag_knowledge_qa.embed.impl;

import io.github.yuri_hack.rag_knowledge_qa.exception.VectorException;
import io.github.yuri_hack.rag_knowledge_qa.embed.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

/**
 * 基于阿里云Embedding模型的向量服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AliEmbeddingServiceImpl implements EmbeddingService {

    @Value("${spring.ai.openai.embedding.normalize:true}")
    private boolean normalize;

    private static final double EPS = 1e-12;

    private final EmbeddingModel embeddingModel;

    /**
     * 获取单段文本的向量
     *
     * @param text 输入文本
     * @return 向量数组
     * @throws VectorException 向量化失败时抛出
     */
    @Override
    public float[] getEmbedding(String text) throws VectorException {
        try {
            Assert.hasText(text, "Text cannot be null or empty");
            float[] embed = embeddingModel.embed(text);
            return normalize ? normalizeVector(embed) : embed;
        } catch (Exception e) {
            log.error("Failed to generate embedding for text: {}", text, e);
            throw new VectorException("Embedding generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * 归一化
     */
    private float[] normalize(float[] embedding) {
        double sum = 0.0;
        for (float v : embedding) {
            sum += (double) v * (double) v;
        }
        double norm = Math.sqrt(sum);
        if (Math.abs(norm) < EPS) norm = 1.0; // 防止除以 0

        float[] out = new float[embedding.length];
        for (int i = 0; i < embedding.length; i++) {
            out[i] = (float) (embedding[i] / norm);
        }
        return out;
    }

    private float[] normalizeVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            return vector;
        }

        // 计算向量模长
        double norm = 0.0;
        for (float value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);

        // 归一化处理
        float[] normalized = new float[vector.length];
        if (norm > 1e-12) { // 避免除零
            for (int i = 0; i < vector.length; i++) {
                normalized[i] = (float) (vector[i] / norm);
            }
        } else {
            // 如果模长接近0，返回原向量或零向量
            System.arraycopy(vector, 0, normalized, 0, vector.length);
        }

        return normalized;
    }


    /**
     * 计算两个向量的余弦相似度
     *
     * @param vector1 向量1
     * @param vector2 向量2
     * @return 相似度得分 (0-1之间)
     */
    @Override
    public double calculateSimilarity(float[] vector1, float[] vector2) {
        Assert.notNull(vector1, "Vector1 cannot be null");
        Assert.notNull(vector2, "Vector2 cannot be null");
        Assert.isTrue(vector1.length == vector2.length, "Vectors must have the same dimension");

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < vector1.length; i++) {
            dotProduct += vector1[i] * vector2[i];
            norm1 += Math.pow(vector1[i], 2);
            norm2 += Math.pow(vector2[i], 2);
        }

        if (norm1 == 0 || norm2 == 0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}