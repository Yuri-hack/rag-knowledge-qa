package io.github.yuri_hack.rag_knowledge_qa.service;

import io.github.yuri_hack.rag_knowledge_qa.dto.response.StreamChatResponse;
import reactor.core.publisher.Flux;

/**
 * 对话编排服务
 */
public interface ChatOrchestrationService {
    /**
     * 处理用户聊天请求，根据意图路由到不同的处理器。
     * sessionId 非空时启用 D6 会话记忆：加载最近几轮 → 改写检索 query → 回答完成后追加历史。
     *
     * @param question       用户原始问题
     * @param sessionId      会话 id，空 = 无记忆单轮（评测/兼容路径）
     * @param rewriteEnabled 是否做多轮 query 改写（可证伪实验的开关）
     * @return 流式响应
     */
    Flux<StreamChatResponse> chatStream(String question, String sessionId, boolean rewriteEnabled);
}