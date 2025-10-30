package io.github.yuri_hack.rag_knowledge_qa.service.impl;

import com.alibaba.dashscope.common.Message;
import io.github.yuri_hack.rag_knowledge_qa.configuration.PromptConfig;
import io.github.yuri_hack.rag_knowledge_qa.configuration.TongYiBaseConfig;
import io.github.yuri_hack.rag_knowledge_qa.dto.ChatResponse;
import io.github.yuri_hack.rag_knowledge_qa.enums.IntentEnum;
import io.github.yuri_hack.rag_knowledge_qa.service.IntentService;
import io.github.yuri_hack.rag_knowledge_qa.service.base.BaseTongYiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class TongYiIntentServiceImpl extends BaseTongYiService implements IntentService {

    public TongYiIntentServiceImpl(TongYiBaseConfig tongYiBaseConfig, PromptConfig promptConfig) {
        super(tongYiBaseConfig, promptConfig);
    }

    @Override
    public IntentEnum classifyIntent(String question) {
        List<Message> messages = buildMessages(promptConfig.getIntentPrompt(), question);
        ChatResponse response = generate(messages, tongYiBaseConfig.getIntentModelConfig());
        if (!response.isSuccess()) {
            log.error("intent error {}", response.getErrorMessage());
            return IntentEnum.DAILY_CHAT;
        }
        return IntentEnum.getByDesc(response.getContent()).orElse(IntentEnum.DAILY_CHAT);
    }
}
