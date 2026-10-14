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
import org.springframework.beans.factory.annotation.Value;
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

    /** 检索漏斗：topK=召回池 N；topRatio 0<x<1 按比例截断，>=1 视为进上下文条数 K（默认 N=20/K=10，深池实验结论） */
    @Value("${rag.search.top-k:20}")
    private int ragSearchTopK;

    @Value("${rag.search.top-ratio:10}")
    private double ragSearchTopRatio;

    /** 证据注入位置：false=<context> 放 system（现状）；true=【检索结果】放 user 轮（与 B 臂同构，实验开关） */
    @Value("${rag.context-in-user-turn:false}")
    private boolean contextInUserTurn;

    /**
     * prompt 版本 = RAG prompt 文本的 sha256 前 8 位，随文本自动变化。
     * 目的：prompt 漂移在评测数字层面必须可见（BC02 教训），不依赖人工升版本号。
     */
    private String promptVersion() {
        String prompt = promptConfig.getRagPrompt() == null ? "" : promptConfig.getRagPrompt();
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("rag-");
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "rag-unknown";
        }
    }

    /** 给流式末尾事件打上 prompt 版本 */
    private Flux<StreamChatResponse> stampPromptVersion(Flux<StreamChatResponse> source) {
        String version = promptVersion();
        return source.map(resp -> resp.isFinished()
                ? resp.toBuilder().promptVersion(version).build() : resp);
    }

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
        return stampPromptVersion(writeCacheDetached(
                buildRAGMessages(question, contextChunks)
                        .flatMapMany(messages -> generateStream(messages, tongYiBaseConfig.getRagModelConfig())),
                answer -> {
                    cacheAnswer(question, answer);
                    log.info("语义缓存重新生成完成，答案已缓存");
                }));
    }

    /**
     * 执行完整RAG流程
     */
    private Flux<StreamChatResponse> executeFullRAG(String question) {
        AtomicReference<List<Long>> documentChunkIds = new AtomicReference<>();

        return stampPromptVersion(writeCacheDetached(
                retrieveContexts(question, documentChunkIds)
                        .flatMapMany(contexts -> buildRAGMessages(question, contexts))
                        .flatMap(messages -> generateStream(messages, tongYiBaseConfig.getRagModelConfig())),
                answer -> {
                    cacheAnswer(question, answer);
                    semanticDocumentCacheService.cacheSemanticDocument(question, documentChunkIds.get());
                    log.info("完整RAG流程完成，缓存已更新");
                }));
    }

    /**
     * 检索知识：返回上下文内容，命中的 chunkId 通过 chunkIdsRef 带出供文档缓存回写
     */
    private Mono<List<String>> retrieveContexts(String question, AtomicReference<List<Long>> chunkIdsRef) {
        return Mono.fromCallable(() -> {
            List<KnowledgeSearchResult> searchResults = knowledgeBaseService
                    .searchKnowledge(SearchRequest.of(question, ragSearchTopK, ragSearchTopRatio));

            chunkIdsRef.set(searchResults.stream()
                    .map(KnowledgeSearchResult::getChunkId)
                    .toList());

            return searchResults.stream()
                    .map(KnowledgeSearchResult::getContent)
                    .toList();
        });
    }

    private void cacheAnswer(String question, String answer) {
        cacheService.cacheExactAnswer(question, answer);
        semanticAnswerCacheService.cacheSemanticAnswer(question, answer);
    }

    /**
     * 构建RAG消息
     */
    private Mono<List<Message>> buildRAGMessages(String question, List<String> contextChunks) {
        return Mono.fromCallable(() -> {
            String context = String.join("\n\n", contextChunks);
            if (contextInUserTurn) {
                // 证据以【检索结果】放入 user 轮，system 提示词去掉 {context} 占位
                String systemPrompt = promptConfig.getRagPrompt().replace("{context}", "");
                return buildMessages(systemPrompt, question + "\n\n【检索结果】\n" + context);
            }
            String ragPrompt = promptConfig.getRagPrompt().replace("{context}", context);
            return buildMessages(ragPrompt, question);
        });
    }
}