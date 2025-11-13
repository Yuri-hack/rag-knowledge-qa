package io.github.yuri_hack.rag_knowledge_qa.service.impl;

import io.github.yuri_hack.rag_knowledge_qa.cache.CacheService;
import io.github.yuri_hack.rag_knowledge_qa.cache.SemanticAnswerCacheService;
import io.github.yuri_hack.rag_knowledge_qa.cache.model.SemanticAnswerCacheHit;
import io.github.yuri_hack.rag_knowledge_qa.dto.response.StreamChatResponse;
import io.github.yuri_hack.rag_knowledge_qa.enums.IntentEnum;
import io.github.yuri_hack.rag_knowledge_qa.service.ChatOrchestrationService;
import io.github.yuri_hack.rag_knowledge_qa.service.DailyChatService;
import io.github.yuri_hack.rag_knowledge_qa.service.IntentService;
import io.github.yuri_hack.rag_knowledge_qa.service.RAGService;
import io.github.yuri_hack.rag_knowledge_qa.util.StreamUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatOrchestrationServiceImpl implements ChatOrchestrationService {

    private final IntentService intentService;
    private final RAGService ragService;
    private final DailyChatService dailyChatService;
    private final CacheService exactCacheService;
    private final SemanticAnswerCacheService semanticAnswerCacheService;

    @Override
    public Flux<StreamChatResponse> chatStream(String question) {
        // 1. 检查精确缓存
        Optional<String> exactAnswer = exactCacheService.getExactAnswer(question);
        if (exactAnswer.isPresent()) {
            log.info("精确缓存命中: question={}", question);
            return StreamUtils.str2StreamChatResponse(exactAnswer.get());
        }

        // 2. 检查语义答案缓存
        Optional<SemanticAnswerCacheHit> semanticAnswerHit = semanticAnswerCacheService.searchSimilarAnswers(question);
        if (semanticAnswerHit.isPresent()) {
            log.info("语义答案缓存命中: question={}, 相似度={}", question, semanticAnswerHit.get().getSimilarity());

            // 回写精确缓存
            String answer = semanticAnswerHit.get().getAnswer();
            exactCacheService.cacheExactAnswer(question, answer);

            return StreamUtils.str2StreamChatResponse(answer);
        }

        // 3. 意图识别
        IntentEnum intent = intentService.classifyIntent(question);
        log.info("用户意图识别结果: intent={}", intent);

        // 根据意图路由到不同的服务
        if (Objects.requireNonNull(intent) == IntentEnum.KNOWLEDGE_BASE) {
            return ragService.handleKnowledgeBaseQuery(question);
        }
        return dailyChatService.handleDailyChat(question);
    }
}