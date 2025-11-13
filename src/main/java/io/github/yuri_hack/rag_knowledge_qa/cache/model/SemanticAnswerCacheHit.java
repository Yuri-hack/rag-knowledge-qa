package io.github.yuri_hack.rag_knowledge_qa.cache.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SemanticAnswerCacheHit {
    private String query;
    private String answer;
    private Double similarity;
}