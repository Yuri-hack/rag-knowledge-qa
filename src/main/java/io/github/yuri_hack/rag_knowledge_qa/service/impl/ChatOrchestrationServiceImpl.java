// ChatOrchestrationServiceImpl.java
package io.github.yuri_hack.rag_knowledge_qa.service.impl;

import io.github.yuri_hack.rag_knowledge_qa.dto.response.StreamChatResponse;
import io.github.yuri_hack.rag_knowledge_qa.enums.IntentEnum;
import io.github.yuri_hack.rag_knowledge_qa.service.ChatOrchestrationService;
import io.github.yuri_hack.rag_knowledge_qa.service.DailyChatService;
import io.github.yuri_hack.rag_knowledge_qa.service.IntentService;
import io.github.yuri_hack.rag_knowledge_qa.service.RAGService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatOrchestrationServiceImpl implements ChatOrchestrationService {

    private final IntentService intentService;
    private final RAGService ragService;
    private final DailyChatService dailyChatService;

    @Override
    public Flux<StreamChatResponse> chatStream(String question) {
        // 1. 意图识别
        IntentEnum intent = intentService.classifyIntent(question);
        log.info("用户意图识别结果: intent={}", intent);

        // 2. 根据意图路由到不同的服务
        if (Objects.requireNonNull(intent) == IntentEnum.KNOWLEDGE_BASE) {
            return ragService.handleKnowledgeBaseQuery(question);
        }
        return dailyChatService.handleDailyChat(question);
    }
}