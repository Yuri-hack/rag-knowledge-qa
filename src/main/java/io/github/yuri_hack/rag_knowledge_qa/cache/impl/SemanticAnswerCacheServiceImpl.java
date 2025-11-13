package io.github.yuri_hack.rag_knowledge_qa.cache.impl;

import io.github.yuri_hack.rag_knowledge_qa.cache.SemanticAnswerCacheService;
import io.github.yuri_hack.rag_knowledge_qa.cache.model.SemanticAnswerCacheHit;
import io.github.yuri_hack.rag_knowledge_qa.config.CacheConfig;
import io.github.yuri_hack.rag_knowledge_qa.embed.EmbeddingService;
import io.github.yuri_hack.rag_knowledge_qa.service.NormalizeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.github.yuri_hack.rag_knowledge_qa.util.VectorUtils.floatArray2Bytes;

@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticAnswerCacheServiceImpl implements SemanticAnswerCacheService {

    private final EmbeddingService embeddingService;
    private final NormalizeService normalizeService;
    private final CacheConfig cacheConfig;
    private final JedisPooled jedisPooled;

    private static final String VECTOR_FIELD = "vector";
    private static final String QUERY_FIELD = "query";
    private static final String ANSWER_FIELD = "answer";
    private static final String DISTANCE_FIELD = "distance";

    @Override
    public void cacheSemanticAnswer(String originalQuery, String answer) {
        if (answer == null || answer.isEmpty()) {
            return;
        }

        try {
            String normalizedQuery = normalizeService.normalizeQuestion(originalQuery);
            float[] queryVector = embeddingService.getEmbedding(normalizedQuery);
            if (queryVector == null) {
                log.warn("无法生成查询向量，跳过语义答案缓存");
                return;
            }

            String cacheKey = generateCacheKey();

            // 设置参数
            Map<String, Object> document = new HashMap<>();
            document.put(QUERY_FIELD, normalizedQuery);
            document.put(ANSWER_FIELD, answer);
            document.put(VECTOR_FIELD, floatArray2Bytes(queryVector));

            // 存储文档到 Redis Hash
            jedisPooled.hset(cacheKey.getBytes(), document.entrySet().stream()
                    .collect(HashMap::new, (m, e) -> m.put(e.getKey().getBytes(),
                            e.getValue() instanceof byte[] ? (byte[]) e.getValue() :
                                    String.valueOf(e.getValue()).getBytes()), HashMap::putAll));

            // 设置过期时间
            jedisPooled.expire(cacheKey, cacheConfig.getSemanticAnswer().getTtlMinutes() * TimeUnit.MINUTES.toSeconds(1));

            log.debug("语义答案缓存已保存, key: {}", cacheKey);
        } catch (Exception e) {
            log.error("保存语义答案缓存失败", e);
        }
    }

    @Override
    public Optional<SemanticAnswerCacheHit> searchSimilarAnswers(String query) {
        if (!cacheConfig.getSemanticAnswer().isEnabled()) {
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

            SemanticAnswerCacheHit bestHit = parseSearchResults(searchResults);
            if (bestHit != null) {
                log.debug("语义答案缓存命中, 原始查询: {}", bestHit.getQuery());
                return Optional.of(bestHit);
            }
        } catch (Exception e) {
            log.error("语义答案缓存搜索失败", e);
        }
        return Optional.empty();
    }

    private SearchResult executeVectorSearch(float[] queryVector) {
        try {
            String query = String.format(
                    "*=>[KNN %d @%s $vec AS %s]",
                    cacheConfig.getSemanticAnswer().getTopK(),
                    VECTOR_FIELD,
                    DISTANCE_FIELD
            );

            Query searchQuery = new Query(query)
                    .addParam("vec", floatArray2Bytes(queryVector))
                    .returnFields(QUERY_FIELD, ANSWER_FIELD, DISTANCE_FIELD)
                    .setSortBy(DISTANCE_FIELD, true)
                    .dialect(2);

            return jedisPooled.ftSearch(cacheConfig.getSemanticAnswer().getIndexName(), searchQuery);
        } catch (Exception e) {
            log.error("执行向量搜索失败", e);
            return null;
        }
    }

    private SemanticAnswerCacheHit parseSearchResults(SearchResult searchResults) {
        if (searchResults.getDocuments().isEmpty()) {
            return null;
        }

        SemanticAnswerCacheHit bestHit = null;
        double bestSimilarity = cacheConfig.getSemanticAnswer().getSimilarityThreshold();

        try {
            for (Document doc : searchResults.getDocuments()) {
                double similarity = 1 - Double.parseDouble(doc.getString(DISTANCE_FIELD));

                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;

                    String cachedQuery = doc.getString(QUERY_FIELD);
                    String answer = doc.getString(ANSWER_FIELD);

                    bestHit = SemanticAnswerCacheHit.builder()
                            .query(cachedQuery)
                            .answer(answer)
                            .similarity(similarity)
                            .build();
                }
            }
        } catch (Exception e) {
            log.error("解析语义答案缓存搜索结果失败", e);
        }
        return bestHit;
    }

    private String generateCacheKey() {
        return cacheConfig.getSemanticAnswer().getCachePrefix() + UUID.randomUUID();
    }
}