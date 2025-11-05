package io.github.yuri_hack.rag_knowledge_qa.cache.impl;

import io.github.yuri_hack.rag_knowledge_qa.cache.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class CacheServiceImpl implements CacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CACHE_PREFIX = "rag:";

    /**
     * 设置缓存
     */
    public void set(String key, Object value, long timeout, TimeUnit timeUnit) {
        try {
            String cacheKey = calcKey(key);
            redisTemplate.opsForValue().set(cacheKey, value, timeout, timeUnit);
            log.debug("缓存设置成功, key: {}", cacheKey);
        } catch (Exception e) {
            log.error("缓存设置失败, key: {}", key, e);
        }
    }

    /**
     * 获取缓存
     */
    public Object get(String key) {
        try {
            String cacheKey = calcKey(key);
            return redisTemplate.opsForValue().get(cacheKey);
        } catch (Exception e) {
            log.error("缓存获取失败, key: {}", key, e);
            return null;
        }
    }

    /**
     * 删除缓存
     */
    public boolean delete(String key) {
        try {
            String cacheKey = calcKey(key);
            return redisTemplate.delete(cacheKey);
        } catch (Exception e) {
            log.error("缓存删除失败, key: {}", key, e);
            return false;
        }
    }

    /**
     * 缓存问答结果
     */
    public void cacheQAResult(String question, String answer) {
        // 缓存1小时
        String key = "qa:" + question.hashCode();
        set(key, answer, 1, TimeUnit.HOURS);
    }

    /**
     * 获取缓存的问答结果
     */
    public String getCachedAnswer(String question) {
        String key = "qa:" + question.hashCode();
        Object result = get(key);
        return result != null ? result.toString() : null;
    }

    private String calcKey(String key) {
        return CACHE_PREFIX + key;
    }
}