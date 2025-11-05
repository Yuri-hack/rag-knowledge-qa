package io.github.yuri_hack.rag_knowledge_qa.cache;

import java.util.concurrent.TimeUnit;

public interface CacheService {

    void set(String key, Object value, long timeout, TimeUnit timeUnit);

    Object get(String key);

    boolean delete(String key);

    void cacheQAResult(String question, String answer);

    String getCachedAnswer(String question);

}
