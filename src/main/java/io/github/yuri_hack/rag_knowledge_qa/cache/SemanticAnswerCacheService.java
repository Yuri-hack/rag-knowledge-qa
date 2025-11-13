package io.github.yuri_hack.rag_knowledge_qa.cache;

import io.github.yuri_hack.rag_knowledge_qa.cache.model.SemanticAnswerCacheHit;

import java.util.Optional;

public interface SemanticAnswerCacheService {
    
    /**
     * 缓存语义答案
     * @param originalQuery 原始问题
     * @param answer 生成的答案
     */
    void cacheSemanticAnswer(String originalQuery, String answer);
    
    /**
     * 搜索相似问题的答案
     * @param query 查询问题
     * @return 语义答案缓存命中结果
     */
    Optional<SemanticAnswerCacheHit> searchSimilarAnswers(String query);
}