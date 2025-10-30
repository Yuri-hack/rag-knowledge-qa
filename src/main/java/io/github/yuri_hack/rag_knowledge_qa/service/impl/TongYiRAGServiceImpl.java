package io.github.yuri_hack.rag_knowledge_qa.service.impl;

import io.github.yuri_hack.rag_knowledge_qa.configuration.PromptConfig;
import io.github.yuri_hack.rag_knowledge_qa.configuration.TongYiBaseConfig;
import io.github.yuri_hack.rag_knowledge_qa.dto.StreamChatResponse;
import io.github.yuri_hack.rag_knowledge_qa.service.RAGService;
import io.github.yuri_hack.rag_knowledge_qa.service.base.BaseTongYiService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class TongYiRAGServiceImpl extends BaseTongYiService implements RAGService {

    public TongYiRAGServiceImpl(TongYiBaseConfig tongYiBaseConfig, PromptConfig promptConfig) {
        super(tongYiBaseConfig, promptConfig);
    }

    @Override
    public Flux<StreamChatResponse> handleKnowledgeBaseQuery(String question, String context) {
        String ragPrompt = promptConfig.getRagPrompt().replace("{context}", context);
        var messages = buildMessages(ragPrompt, question);
        return generateStream(messages, tongYiBaseConfig.getRagModelConfig());
    }
}