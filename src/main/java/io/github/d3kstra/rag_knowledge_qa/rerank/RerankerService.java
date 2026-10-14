package io.github.d3kstra.rag_knowledge_qa.rerank;

import io.github.d3kstra.rag_knowledge_qa.dto.internal.KnowledgeSearchResult;
import io.github.d3kstra.rag_knowledge_qa.dto.internal.RerankResult;

import java.util.List;

public interface RerankerService {

    List<RerankResult> rerankKnowledgeResults(String query, List<KnowledgeSearchResult> knowledgeResults);
}
