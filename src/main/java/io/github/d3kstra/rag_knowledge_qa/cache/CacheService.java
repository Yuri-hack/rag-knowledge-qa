package io.github.d3kstra.rag_knowledge_qa.cache;

import java.util.Optional;

public interface CacheService {

    Optional<String> getExactAnswer(String question);

    void cacheExactAnswer(String question, String answer);
}