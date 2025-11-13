package io.github.yuri_hack.rag_knowledge_qa.cache.impl;

import io.github.yuri_hack.rag_knowledge_qa.cache.CacheService;
import io.github.yuri_hack.rag_knowledge_qa.config.CacheConfig;
import io.github.yuri_hack.rag_knowledge_qa.util.SimpleNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class CacheServiceImpl implements CacheService {
    private final RedisTemplate<String, Object> redisTemplate;
    private final SimpleNormalizer simpleNormalizer;
    private final CacheConfig config;

    private static final String CACHE_PREFIX = "rag:exact:";

    @Override
    public Optional<String> getExactAnswer(String question) {
        if (!config.getExact().isEnabled()) return Optional.empty();

        try {
            String key = buildCacheKey(question);
            Object result = redisTemplate.opsForValue().get(key);
            return result != null ? Optional.of(result.toString()) : Optional.empty();
        } catch (Exception e) {
            log.error("获取精确缓存失败", e);
            return Optional.empty();
        }
    }

    @Override
    public void cacheExactAnswer(String question, String answer) {
        if (!config.getExact().isEnabled()) return;

        try {
            String key = buildCacheKey(question);
            redisTemplate.opsForValue().set(
                    key, answer, config.getExact().getTtlMinutes(), TimeUnit.MINUTES
            );
        } catch (Exception e) {
            log.error("缓存精确答案失败", e);
        }
    }

    private String buildCacheKey(String question) {
        // 这里只做基本归一化
        return CACHE_PREFIX + simpleNormalizer.normalize(question);
    }
}