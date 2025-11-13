package io.github.yuri_hack.rag_knowledge_qa.cache.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SemanticDocumentCache {
    private String query;
    private List<String> contextChunks;
    private List<Long> documentChunkIds;
}