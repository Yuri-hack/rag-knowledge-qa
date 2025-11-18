package io.github.yuri_hack.rag_knowledge_qa.service;

import io.github.yuri_hack.rag_knowledge_qa.dto.response.StreamChatResponse;
import reactor.core.publisher.Flux;

/**
 * 自适应问答服务
 * 由llm自行判断是否要基于知识进行回答
 */
public interface AdaptiveAnswerService {

    Flux<StreamChatResponse> handleAmbiguousQuery(String question);
}
