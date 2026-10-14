package io.github.d3kstra.rag_knowledge_qa.knowledge;

import io.github.d3kstra.rag_knowledge_qa.dto.internal.KnowledgeSearchResult;
import io.github.d3kstra.rag_knowledge_qa.dto.request.FileUploadRequest;
import io.github.d3kstra.rag_knowledge_qa.dto.request.SearchRequest;

import java.util.List;

public interface KnowledgeBaseService {

    String uploadAndProcessFile(FileUploadRequest request);

    List<KnowledgeSearchResult> searchKnowledge(SearchRequest request);

    double getMaxSimilarity(String query);
}
