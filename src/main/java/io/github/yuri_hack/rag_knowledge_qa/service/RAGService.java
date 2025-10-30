package io.github.yuri_hack.rag_knowledge_qa.service;

import io.github.yuri_hack.rag_knowledge_qa.dto.StreamChatResponse;
import reactor.core.publisher.Flux;

public interface RAGService {

    Flux<StreamChatResponse> handleKnowledgeBaseQuery(String question, String context);
}
