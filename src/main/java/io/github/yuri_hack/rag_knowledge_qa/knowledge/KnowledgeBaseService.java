package io.github.yuri_hack.rag_knowledge_qa.knowledge;

import io.github.yuri_hack.rag_knowledge_qa.dto.internal.KnowledgeSearchResult;
import io.github.yuri_hack.rag_knowledge_qa.dto.request.FileUploadRequest;
import io.github.yuri_hack.rag_knowledge_qa.dto.request.SearchRequest;

import java.util.List;

public interface KnowledgeBaseService {

    String uploadAndProcessFile(FileUploadRequest request);

    List<KnowledgeSearchResult> searchKnowledge(SearchRequest request);

    double getMaxSimilarity(String query);
}
