package io.github.d3kstra.rag_knowledge_qa.service.impl;

import io.github.d3kstra.rag_knowledge_qa.cache.CacheService;
import io.github.d3kstra.rag_knowledge_qa.cache.SemanticAnswerCacheService;
import io.github.d3kstra.rag_knowledge_qa.cache.model.SemanticAnswerCacheHit;
import io.github.d3kstra.rag_knowledge_qa.config.RoutingConfig;
import io.github.d3kstra.rag_knowledge_qa.dto.response.StreamChatResponse;
import io.github.d3kstra.rag_knowledge_qa.knowledge.KnowledgeBaseService;
import io.github.d3kstra.rag_knowledge_qa.service.AdaptiveAnswerService;
import io.github.d3kstra.rag_knowledge_qa.service.ChatOrchestrationService;
import io.github.d3kstra.rag_knowledge_qa.service.ChatSessionStore;
import io.github.d3kstra.rag_knowledge_qa.service.ChatSessionStore.Turn;
import io.github.d3kstra.rag_knowledge_qa.service.DailyChatService;
import io.github.d3kstra.rag_knowledge_qa.service.IntentService;
import io.github.d3kstra.rag_knowledge_qa.service.QueryRewriteService;
import io.github.d3kstra.rag_knowledge_qa.service.RAGService;
import io.github.d3kstra.rag_knowledge_qa.util.ReactorUtils;
import io.github.d3kstra.rag_knowledge_qa.util.StreamUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
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
    private final KnowledgeBaseService knowledgeBaseService;
    private final RoutingConfig routingConfig;
    private final AdaptiveAnswerService adaptiveAnswerService;
    private final ChatSessionStore sessionStore;
    private final QueryRewriteService queryRewriteService;

    @Override
    public Flux<StreamChatResponse> chatStream(String question, String sessionId, boolean rewriteEnabled) {
        // 0. D6 会话记忆：加载历史 → 改写出独立检索 query（缓存 key 与检索共用它）。
        //    注意：追加钩子只要 sessionId 存在就安装——若等"有历史"才装，第一轮永远不会入库，
        //    会话就永远是空记忆（首轮冷启动是会话的常态入口）。
        String effectiveQuery = question;
        if (sessionId != null && !sessionId.isBlank()) {
            List<Turn> history = sessionStore.load(sessionId);
            if (!history.isEmpty() && rewriteEnabled) {
                effectiveQuery = queryRewriteService.rewrite(question, history);
            }
            log.info("多轮会话: sessionId={}, 历史{}轮, 问题=[{}] → 检索query=[{}]",
                    sessionId, history.size(), question, effectiveQuery);

            Flux<StreamChatResponse> pipeline = route(effectiveQuery);
            // 回答完成后追加会话历史（记原始问题，非改写后的）；与客户端断连无关
            return ReactorUtils.writeAnswerDetached(pipeline,
                    answer -> sessionStore.append(sessionId, question, answer));
        }
        return route(effectiveQuery);
    }

    /** 意图路由（原 chatStream 主体）：入参已是与记忆无关的独立问题 */
    private Flux<StreamChatResponse> route(String question) {
        // 1. 检查精确缓存
        Optional<Flux<StreamChatResponse>> exactHit = tryExactCache(question);
        if (exactHit.isPresent()) {
            return exactHit.get();
        }

        // 2. 检查语义答案缓存
        Optional<Flux<StreamChatResponse>> semanticHit = trySemanticAnswerCache(question);
        if (semanticHit.isPresent()) {
            return semanticHit.get();
        }

        // 3. 意图识别
        double intentScore = intentService.getKnowledgeIntentScore(question);
        double similarity = knowledgeBaseService.getMaxSimilarity(question);
        // 根据意图路由到不同的服务
        if (intentScore > routingConfig.getIntentHigh() || similarity > routingConfig.getIntentHigh()) {
            return ragService.handleKnowledgeBaseQuery(question);
        } else if (intentScore < routingConfig.getIntentLow() && similarity < routingConfig.getIntentLow()) {
            return dailyChatService.handleDailyChat(question);
        } else {
            return adaptiveAnswerService.handleAmbiguousQuery(question);
        }
    }

    private Optional<Flux<StreamChatResponse>> tryExactCache(String question) {
        Optional<String> exactAnswer = exactCacheService.getExactAnswer(question);
        if (exactAnswer.isEmpty()) {
            return Optional.empty();
        }
        log.info("精确缓存命中: question={}", question);
        return Optional.of(StreamUtils.str2StreamChatResponse(exactAnswer.get()));
    }

    private Optional<Flux<StreamChatResponse>> trySemanticAnswerCache(String question) {
        Optional<SemanticAnswerCacheHit> semanticAnswerHit = semanticAnswerCacheService.searchSimilarAnswers(question);
        if (semanticAnswerHit.isEmpty()) {
            return Optional.empty();
        }
        log.info("语义答案缓存命中: question={}, 相似度={}", question, semanticAnswerHit.get().getSimilarity());

        // 回写精确缓存
        String answer = semanticAnswerHit.get().getAnswer();
        exactCacheService.cacheExactAnswer(question, answer);

        return Optional.of(StreamUtils.str2StreamChatResponse(answer));
    }
}