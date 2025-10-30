package io.github.yuri_hack.rag_knowledge_qa.service.impl;

import com.alibaba.dashscope.common.Message;
import io.github.yuri_hack.rag_knowledge_qa.configuration.PromptConfig;
import io.github.yuri_hack.rag_knowledge_qa.configuration.TongYiBaseConfig;
import io.github.yuri_hack.rag_knowledge_qa.dto.StreamChatResponse;
import io.github.yuri_hack.rag_knowledge_qa.service.DailyChatService;
import io.github.yuri_hack.rag_knowledge_qa.service.base.BaseTongYiService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class TongYiDailyChatServiceImpl extends BaseTongYiService implements DailyChatService {

    public TongYiDailyChatServiceImpl(TongYiBaseConfig tongYiBaseConfig, PromptConfig promptConfig) {
        super(tongYiBaseConfig, promptConfig);
    }

    @Override
    public Flux<StreamChatResponse> handleDailyChat(String question) {
        List<Message> messages = buildMessages(promptConfig.getDailyPrompt(), question);
        return generateStream(messages, tongYiBaseConfig.getCommonModelConfig());
    }
}