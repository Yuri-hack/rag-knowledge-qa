package io.github.d3kstra.rag_knowledge_qa.service;

import io.github.d3kstra.rag_knowledge_qa.dto.response.StreamChatResponse;
import reactor.core.publisher.Flux;

public interface RAGService {

    Flux<StreamChatResponse> handleKnowledgeBaseQuery(String question);
}
