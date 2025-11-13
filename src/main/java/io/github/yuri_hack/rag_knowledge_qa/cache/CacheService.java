package io.github.yuri_hack.rag_knowledge_qa.cache;

import java.util.Optional;

public interface CacheService {

    Optional<String> getExactAnswer(String question);

    void cacheExactAnswer(String question, String answer);
}