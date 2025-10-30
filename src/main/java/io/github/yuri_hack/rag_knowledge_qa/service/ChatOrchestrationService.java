package io.github.yuri_hack.rag_knowledge_qa.service;

import io.github.yuri_hack.rag_knowledge_qa.dto.StreamChatResponse;
import reactor.core.publisher.Flux;

/**
 * 对话编排服务
 */
public interface ChatOrchestrationService {
    /**
     * 处理用户聊天请求，根据意图路由到不同的处理器
     * @param question 用户问题
     * @param topK 检索文档数量
     * @return 流式响应
     */
    Flux<StreamChatResponse> chatStream(String question, int topK);
}