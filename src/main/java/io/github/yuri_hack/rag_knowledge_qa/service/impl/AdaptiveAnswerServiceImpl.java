package io.github.yuri_hack.rag_knowledge_qa.service.impl;

import com.alibaba.dashscope.common.Message;
import io.github.yuri_hack.rag_knowledge_qa.cache.CacheService;
import io.github.yuri_hack.rag_knowledge_qa.cache.SemanticAnswerCacheService;
import io.github.yuri_hack.rag_knowledge_qa.config.PromptConfig;
import io.github.yuri_hack.rag_knowledge_qa.config.TongYiBaseConfig;
import io.github.yuri_hack.rag_knowledge_qa.dto.internal.KnowledgeSearchResult;
import io.github.yuri_hack.rag_knowledge_qa.dto.request.SearchRequest;
import io.github.yuri_hack.rag_knowledge_qa.dto.response.StreamChatResponse;
import io.github.yuri_hack.rag_knowledge_qa.knowledge.KnowledgeBaseService;
import io.github.yuri_hack.rag_knowledge_qa.service.AdaptiveAnswerService;
import io.github.yuri_hack.rag_knowledge_qa.service.base.BaseTongYiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AdaptiveAnswerServiceImpl extends BaseTongYiService implements AdaptiveAnswerService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final SemanticAnswerCacheService semanticAnswerCacheService;
    private final CacheService cacheService;

    public AdaptiveAnswerServiceImpl(TongYiBaseConfig tongYiBaseConfig, PromptConfig promptConfig,
                                     KnowledgeBaseService knowledgeBaseService,
                                     SemanticAnswerCacheService semanticAnswerCacheService, CacheService cacheService) {
        super(tongYiBaseConfig, promptConfig);
        this.knowledgeBaseService = knowledgeBaseService;
        this.semanticAnswerCacheService = semanticAnswerCacheService;
        this.cacheService = cacheService;
    }

    @Override
    public Flux<StreamChatResponse> handleAmbiguousQuery(String question) {
        // Flux.defer：检索延迟到订阅时才执行——修复 eager 检索（组装期就跑，无人订阅也烧一次检索）
        return Flux.defer(() -> {
            // 构造prompt
            List<KnowledgeSearchResult> results = knowledgeBaseService.searchKnowledge(SearchRequest.of(question));
            String context = results.stream()
                    .map(KnowledgeSearchResult::getContent)
                    .collect(Collectors.joining("\n\n"));
            String prompt = promptConfig.getAdaptiveAnswerPrompt().replace("{retrieved_docs}", context);

            // 构造message
            List<Message> messages = buildMessages(prompt, question);

            // 生成答案（缓存回写与订阅者解耦）
            return writeCacheDetached(
                    generateStream(messages, tongYiBaseConfig.getAdaptiveModelConfig()),
                    answer -> {
                        cacheService.cacheExactAnswer(question, answer);
                        semanticAnswerCacheService.cacheSemanticAnswer(question, answer);
                        log.info("自适应回答完成，缓存已更新");
                    });
        });
    }
}
