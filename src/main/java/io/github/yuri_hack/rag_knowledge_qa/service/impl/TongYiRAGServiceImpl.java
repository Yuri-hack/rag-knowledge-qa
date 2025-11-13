// TongYiRAGServiceImpl.java (修改后)
package io.github.yuri_hack.rag_knowledge_qa.service.impl;

import com.alibaba.dashscope.common.Message;
import io.github.yuri_hack.rag_knowledge_qa.cache.CacheService;
import io.github.yuri_hack.rag_knowledge_qa.cache.SemanticAnswerCacheService;
import io.github.yuri_hack.rag_knowledge_qa.cache.SemanticDocumentCacheService;
import io.github.yuri_hack.rag_knowledge_qa.cache.model.SemanticDocumentCache;
import io.github.yuri_hack.rag_knowledge_qa.config.PromptConfig;
import io.github.yuri_hack.rag_knowledge_qa.config.TongYiBaseConfig;
import io.github.yuri_hack.rag_knowledge_qa.dto.internal.KnowledgeSearchResult;
import io.github.yuri_hack.rag_knowledge_qa.dto.request.SearchRequest;
import io.github.yuri_hack.rag_knowledge_qa.dto.response.StreamChatResponse;
import io.github.yuri_hack.rag_knowledge_qa.knowledge.KnowledgeBaseService;
import io.github.yuri_hack.rag_knowledge_qa.service.RAGService;
import io.github.yuri_hack.rag_knowledge_qa.service.base.BaseTongYiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class TongYiRAGServiceImpl extends BaseTongYiService implements RAGService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final SemanticDocumentCacheService semanticDocumentCacheService;
    private final SemanticAnswerCacheService semanticAnswerCacheService;
    private final CacheService cacheService;

    public TongYiRAGServiceImpl(TongYiBaseConfig tongYiBaseConfig,
                                PromptConfig promptConfig,
                                KnowledgeBaseService knowledgeBaseService,
                                SemanticDocumentCacheService semanticDocumentCacheService,
                                SemanticAnswerCacheService semanticAnswerCacheService,
                                CacheService cacheService) {
        super(tongYiBaseConfig, promptConfig);
        this.knowledgeBaseService = knowledgeBaseService;
        this.semanticDocumentCacheService = semanticDocumentCacheService;
        this.semanticAnswerCacheService = semanticAnswerCacheService;
        this.cacheService = cacheService;
    }

    @Override
    public Flux<StreamChatResponse> handleKnowledgeBaseQuery(String question) {
        log.info("处理知识库查询: {}", question);

        // 检查语义缓存
        Optional<SemanticDocumentCache> semanticCacheHit = semanticDocumentCacheService.searchSimilarDocument(question);
        if (semanticCacheHit.isPresent()) {
            log.info("语义文档缓存命中,{}", semanticCacheHit.get());
            return generateWithCachedContext(question, semanticCacheHit.get().getContextChunks());
        }

        // 缓存未命中，执行完整RAG流程
        return executeFullRAG(question);
    }

    /**
     * 使用缓存上下文重新生成答案
     */
    private Flux<StreamChatResponse> generateWithCachedContext(String question, List<String> contextChunks) {
        AtomicReference<StringBuilder> fullAnswer = new AtomicReference<>(new StringBuilder());

        return buildRAGMessages(question, contextChunks)
                .flatMapMany(messages -> generateStream(messages, tongYiBaseConfig.getRagModelConfig()))
                .map(streamResponse -> {
                    // 累积完整答案用于缓存
                    if (streamResponse.getContent() != null) {
                        fullAnswer.get().append(streamResponse.getContent());
                    }
                    return streamResponse;
                })
                .doOnComplete(() -> {
                    // 缓存新生成的答案
                    String answer = fullAnswer.get().toString();
                    cacheService.cacheExactAnswer(question, answer);
                    semanticAnswerCacheService.cacheSemanticAnswer(question, answer);
                    log.info("语义缓存重新生成完成，答案已缓存");
                });
    }

    /**
     * 执行完整RAG流程
     */
    private Flux<StreamChatResponse> executeFullRAG(String question) {
        AtomicReference<StringBuilder> fullAnswer = new AtomicReference<>(new StringBuilder());
        AtomicReference<List<Long>> documentChunkIds = new AtomicReference<>();

        return Mono.fromCallable(() -> {
                    // 检索知识
                    List<KnowledgeSearchResult> searchResults = knowledgeBaseService
                            .searchKnowledge(SearchRequest.of(question));

                    List<String> contexts = searchResults.stream()
                            .map(KnowledgeSearchResult::getContent)
                            .toList();

                    List<Long> chunkIds = searchResults.stream()
                            .map(KnowledgeSearchResult::getChunkId)
                            .toList();
                    documentChunkIds.set(chunkIds);

                    return contexts;
                })
                .flatMapMany(contexts -> buildRAGMessages(question, contexts))
                .flatMap(messages -> generateStream(messages, tongYiBaseConfig.getRagModelConfig()))
                .map(streamResponse -> {
                    // 累积完整答案
                    if (streamResponse.getContent() != null) {
                        fullAnswer.get().append(streamResponse.getContent());
                    }
                    return streamResponse;
                })
                .doOnComplete(() -> {
                    // 缓存结果
                    String answer = fullAnswer.get().toString();
                    cacheService.cacheExactAnswer(question, answer);
                    semanticAnswerCacheService.cacheSemanticAnswer(question, answer);
                    semanticDocumentCacheService.cacheSemanticDocument(question, documentChunkIds.get());
                    log.info("完整RAG流程完成，缓存已更新");
                });
    }

    /**
     * 构建RAG消息
     */
    private Mono<List<Message>> buildRAGMessages(String question, List<String> contextChunks) {
        return Mono.fromCallable(() -> {
            String context = String.join("\n\n", contextChunks);
            String ragPrompt = promptConfig.getRagPrompt().replace("{context}", context);
            return buildMessages(ragPrompt, question);
        });
    }
}