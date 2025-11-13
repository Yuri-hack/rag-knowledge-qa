package io.github.yuri_hack.rag_knowledge_qa.cache.impl;

import io.github.yuri_hack.rag_knowledge_qa.cache.SemanticDocumentCacheService;
import io.github.yuri_hack.rag_knowledge_qa.cache.model.SemanticDocumentCache;
import io.github.yuri_hack.rag_knowledge_qa.config.CacheConfig;
import io.github.yuri_hack.rag_knowledge_qa.embed.EmbeddingService;
import io.github.yuri_hack.rag_knowledge_qa.entity.DocumentChunk;
import io.github.yuri_hack.rag_knowledge_qa.repository.DocumentChunkRepository;
import io.github.yuri_hack.rag_knowledge_qa.service.NormalizeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.github.yuri_hack.rag_knowledge_qa.util.VectorUtils.floatArray2Bytes;

@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticDocumentCacheServiceImpl implements SemanticDocumentCacheService {

    private final EmbeddingService embeddingService;
    private final NormalizeService normalizeService;
    private final CacheConfig cacheConfig;
    private final JedisPooled jedisPooled;
    private final DocumentChunkRepository documentChunkRepository;

    private static final String VECTOR_FIELD = "vector";
    private static final String QUERY_FIELD = "query";
    private static final String DOC_IDS_FIELD = "docIds";
    private static final String DISTANCE_FIELD = "distance";

    @Override
    public void cacheSemanticDocument(String originalQuery, List<Long> documentChunkIds) {
        if (!cacheConfig.getSemantic().isEnabled() || documentChunkIds == null || documentChunkIds.isEmpty()) {
            return;
        }

        try {
            String normalizedQuery = normalizeService.normalizeQuestion(originalQuery);
            float[] queryVector = embeddingService.getEmbedding(normalizedQuery);
            if (queryVector == null) {
                log.warn("无法生成查询向量，跳过语义缓存");
                return;
            }

            String cacheKey = generateCacheKey();

            String docIds = documentChunkIds.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining("|||"));

            // 设置参数
            Map<String, Object> document = new HashMap<>();
            document.put(QUERY_FIELD, normalizedQuery);
            document.put(DOC_IDS_FIELD, docIds);
            document.put(VECTOR_FIELD, floatArray2Bytes(queryVector));

            // 存储文档到 Redis Hash
            jedisPooled.hset(cacheKey.getBytes(), document.entrySet().stream()
                    .collect(HashMap::new, (m, e) -> m.put(e.getKey().getBytes(),
                            e.getValue() instanceof byte[] ? (byte[]) e.getValue() :
                                    String.valueOf(e.getValue()).getBytes()), HashMap::putAll));

            // 设置过期时间
            jedisPooled.expire(cacheKey, cacheConfig.getSemantic().getTtlHours() * TimeUnit.HOURS.toSeconds(1));

            log.debug("语义缓存已保存, key: {}, 上下文块数: {}", cacheKey, documentChunkIds.size());
        } catch (Exception e) {
            log.error("保存语义缓存失败", e);
        }
    }

    @Override
    public Optional<SemanticDocumentCache> searchSimilarDocument(String query) {
        if (!cacheConfig.getSemantic().isEnabled()) {
            return Optional.empty();
        }

        try {
            String normalizedQuery = normalizeService.normalizeQuestion(query);
            float[] queryVector = embeddingService.getEmbedding(normalizedQuery);
            if (queryVector == null) {
                return Optional.empty();
            }

            SearchResult searchResults = executeVectorSearch(queryVector);

            if (searchResults == null || searchResults.getDocuments().isEmpty()) {
                return Optional.empty();
            }

            SemanticDocumentCache bestHit = parseSearchResults(searchResults);
            if (bestHit != null) {
                log.debug("语义缓存命中, 原始查询: {}", bestHit.getQuery());
                enrichSemanticCacheHit(bestHit);
                return Optional.of(bestHit);
            }
        } catch (Exception e) {
            log.error("语义缓存搜索失败", e);
        }
        return Optional.empty();
    }

    private void enrichSemanticCacheHit(SemanticDocumentCache bestHit) {
        List<DocumentChunk> chunks = documentChunkRepository.findByIdIn(bestHit.getDocumentChunkIds());
        List<String> contextChunks = chunks.stream()
                .map(DocumentChunk::getContent)
                .toList();
        bestHit.setContextChunks(contextChunks);
    }

    private SearchResult executeVectorSearch(float[] queryVector) {
        try {
            String query = String.format(
                    "*=>[KNN %d @%s $vec AS %s]",
                    cacheConfig.getSemantic().getTopK(),
                    VECTOR_FIELD,
                    DISTANCE_FIELD
            );

            // 使用 Query 对象构建查询
            Query searchQuery = new Query(query)
                    .addParam("vec", floatArray2Bytes(queryVector))
                    .returnFields(QUERY_FIELD, DOC_IDS_FIELD, DISTANCE_FIELD)
                    .setSortBy(DISTANCE_FIELD, true)
                    .dialect(2);

            return jedisPooled.ftSearch(cacheConfig.getSemantic().getIndexName(), searchQuery);
        } catch (Exception e) {
            log.error("执行向量搜索失败", e);
            return null;
        }
    }

    private SemanticDocumentCache parseSearchResults(SearchResult searchResults) {
        if (searchResults.getDocuments().isEmpty()) {
            return null;
        }

        SemanticDocumentCache bestHit = null;
        double bestSimilarity = cacheConfig.getSemantic().getSimilarityThreshold();

        try {
            for (Document doc : searchResults.getDocuments()) {
                double similarity = 1 - Double.parseDouble(doc.getString(DISTANCE_FIELD));

                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;

                    String docIds = doc.getString(DOC_IDS_FIELD);
                    List<Long> chunkIds = Arrays.stream(docIds.split("\\|\\|\\|"))
                            .map(Long::parseLong)
                            .toList();

                    bestHit = SemanticDocumentCache.builder()
                            .query(doc.getString(QUERY_FIELD))
                            .documentChunkIds(chunkIds)
                            .build();
                }
            }
        } catch (Exception e) {
            log.error("解析语义缓存搜索结果失败", e);
        }
        return bestHit;
    }

    private String generateCacheKey() {
        return cacheConfig.getSemantic().getCachePrefix() + UUID.randomUUID();
    }
}