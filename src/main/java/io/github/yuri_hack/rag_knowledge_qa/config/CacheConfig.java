package io.github.yuri_hack.rag_knowledge_qa.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rag.cache")
public class CacheConfig {

    // 精确缓存配置
    private ExactCache exact = new ExactCache();

    // 语义答案缓存配置
    private SemanticAnswerCache semanticAnswer = new SemanticAnswerCache();

    // 语义文档缓存配置
    private SemanticCache semantic = new SemanticCache();

    @Data
    public static class ExactCache {
        private long ttlMinutes = 60; // 默认1小时
        private boolean enabled = true;
    }

    @Data
    public static class SemanticAnswerCache {
        private long ttlMinutes = 60; // 默认1小时
        private double similarityThreshold = 0.95; // 高相似度阈值
        private int topK = 5; // 最大返回数量
        private boolean enabled = true;
        private String indexName = "rag_answer_semantic_idx";
        private String cachePrefix = "rag:answer:semantic";
    }

    @Data
    public static class SemanticCache {
        private long ttlHours = 24; // 默认24小时
        private double similarityThreshold = 0.85; // 中高相似度阈值
        private int topK = 5; // 最大返回数量
        private boolean enabled = true;
        private String indexName = "rag_document_semantic_idx";
        private String cachePrefix = "rag:document:semantic:";
    }
}