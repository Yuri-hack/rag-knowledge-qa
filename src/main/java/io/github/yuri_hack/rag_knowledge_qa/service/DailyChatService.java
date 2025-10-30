package io.github.yuri_hack.rag_knowledge_qa.service;

import io.github.yuri_hack.rag_knowledge_qa.dto.StreamChatResponse;
import reactor.core.publisher.Flux;

public interface DailyChatService {
    /**
     * 处理日常聊天请求
     * @param question 用户问题
     * @return 流式响应
     */
    Flux<StreamChatResponse> handleDailyChat(String question);
}