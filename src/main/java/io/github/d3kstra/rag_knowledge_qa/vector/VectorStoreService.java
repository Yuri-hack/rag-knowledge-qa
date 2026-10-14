package io.github.d3kstra.rag_knowledge_qa.vector;

import io.github.d3kstra.rag_knowledge_qa.dto.internal.VectorSearchResult;
import io.github.d3kstra.rag_knowledge_qa.entity.DocumentChunk;

import java.util.List;

public interface VectorStoreService {

    List<Long> insertVectors(List<DocumentChunk> chunks);

    List<VectorSearchResult> searchSimilarVectors(float[] queryVector, int topK);
}
