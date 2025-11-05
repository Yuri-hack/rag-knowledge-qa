package io.github.yuri_hack.rag_knowledge_qa.rerank;

import io.github.yuri_hack.rag_knowledge_qa.dto.internal.KnowledgeSearchResult;
import io.github.yuri_hack.rag_knowledge_qa.dto.internal.RerankResult;

import java.util.List;

public interface RerankerService {

    List<RerankResult> rerankKnowledgeResults(String query, List<KnowledgeSearchResult> knowledgeResults);
}
