package io.github.yuri_hack.rag_knowledge_qa.service.impl;

import com.alibaba.dashscope.common.Message;
import io.github.yuri_hack.rag_knowledge_qa.config.PromptConfig;
import io.github.yuri_hack.rag_knowledge_qa.config.TongYiBaseConfig;
import io.github.yuri_hack.rag_knowledge_qa.dto.response.ChatResponse;
import io.github.yuri_hack.rag_knowledge_qa.service.NormalizeService;
import io.github.yuri_hack.rag_knowledge_qa.service.base.BaseTongYiService;
import io.github.yuri_hack.rag_knowledge_qa.util.SimpleNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class TongYiNormalizeServiceImpl extends BaseTongYiService implements NormalizeService {

    private final SimpleNormalizer simpleNormalizer;

    public TongYiNormalizeServiceImpl(TongYiBaseConfig tongYiBaseConfig, PromptConfig promptConfig,
                                      SimpleNormalizer simpleNormalizer) {
        super(tongYiBaseConfig, promptConfig);
        this.simpleNormalizer = simpleNormalizer;
    }

    @Override
    public String normalizeQuestion(String question) {
        if (StringUtils.isBlank(question)) {
            return question;
        }

        // 构建归一化消息
        List<Message> messages = buildMessages(promptConfig.getNormalizePrompt(), question);
        
        // 调用通义千问API
        ChatResponse response = generate(messages, tongYiBaseConfig.getNormalizeModelConfig());
        
        if (!response.isSuccess()) {
            log.error("问题归一化失败，问题: {}, 错误: {}", question, response.getErrorMessage());
            // 失败时降级简单处理
            return simpleNormalizer.normalize(question);
        }
        
        String normalized = response.getContent().trim();
        log.debug("问题归一化完成 - 原始: [{}], 归一化: [{}]", question, normalized);
        
        return normalized;
    }
}