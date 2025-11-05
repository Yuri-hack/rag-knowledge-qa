package io.github.yuri_hack.rag_knowledge_qa.service.impl;

import com.alibaba.dashscope.common.Message;
import io.github.yuri_hack.rag_knowledge_qa.config.PromptConfig;
import io.github.yuri_hack.rag_knowledge_qa.config.TongYiBaseConfig;
import io.github.yuri_hack.rag_knowledge_qa.dto.internal.KnowledgeSearchResult;
import io.github.yuri_hack.rag_knowledge_qa.dto.request.SearchRequest;
import io.github.yuri_hack.rag_knowledge_qa.dto.response.StreamChatResponse;
import io.github.yuri_hack.rag_knowledge_qa.knowledge.KnowledgeBaseService;
import io.github.yuri_hack.rag_knowledge_qa.service.RAGService;
import io.github.yuri_hack.rag_knowledge_qa.service.base.BaseTongYiService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TongYiRAGServiceImpl extends BaseTongYiService implements RAGService {

    private final KnowledgeBaseService knowledgeBaseService;

    public TongYiRAGServiceImpl(TongYiBaseConfig tongYiBaseConfig, PromptConfig promptConfig, KnowledgeBaseService knowledgeBaseService) {
        super(tongYiBaseConfig, promptConfig);
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Override
    public Flux<StreamChatResponse> handleKnowledgeBaseQuery(String question) {
        // 知识库搜索相关文档
        List<KnowledgeSearchResult> knowledgeSearchResults =
                knowledgeBaseService.searchKnowledge(SearchRequest.of(question));

        String context = knowledgeSearchResults.stream()
                .map(KnowledgeSearchResult::getContent)
                .collect(Collectors.joining(" \n "));

        // 构造prompt
        String ragPrompt = promptConfig.getRagPrompt().replace("{context}", context);
        List<Message> messages = buildMessages(ragPrompt, question);

        return generateStream(messages, tongYiBaseConfig.getRagModelConfig());
    }
}