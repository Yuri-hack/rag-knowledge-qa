package io.github.yuri_hack.rag_knowledge_qa.cache;

import io.github.yuri_hack.rag_knowledge_qa.cache.model.SemanticDocumentCache;

import java.util.List;
import java.util.Optional;

/**
 * 语义相似缓存
 * question -> context
 */
public interface SemanticDocumentCacheService {

    /**
     * 缓存语义文档
     */
    void cacheSemanticDocument(String originalQuery, List<Long> documentChunkIds);

    /**
     * 搜索相似查询
     */
    Optional<SemanticDocumentCache> searchSimilarDocument(String query);
}