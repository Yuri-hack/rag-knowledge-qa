package io.github.yuri_hack.rag_knowledge_qa.service.impl;

import com.alibaba.dashscope.common.Message;
import io.github.yuri_hack.rag_knowledge_qa.config.PromptConfig;
import io.github.yuri_hack.rag_knowledge_qa.config.TongYiBaseConfig;
import io.github.yuri_hack.rag_knowledge_qa.dto.response.ChatResponse;
import io.github.yuri_hack.rag_knowledge_qa.service.IntentService;
import io.github.yuri_hack.rag_knowledge_qa.service.base.BaseTongYiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class TongYiIntentServiceImpl extends BaseTongYiService implements IntentService {

    // 用于提取数字的正则表达式
    private static final Pattern NUMBER_PATTERN = Pattern.compile("0\\.\\d+|1\\.0");

    public TongYiIntentServiceImpl(TongYiBaseConfig tongYiBaseConfig, PromptConfig promptConfig) {
        super(tongYiBaseConfig, promptConfig);
    }

    @Override
    public Double getKnowledgeIntentScore(String question) {
        List<Message> messages = buildMessages(promptConfig.getIntentPrompt(), question);
        ChatResponse response = generate(messages, tongYiBaseConfig.getIntentModelConfig());

        if (!response.isSuccess()) {
            log.error("Intent classification error: {}", response.getErrorMessage());
            return 0.0; // 默认返回0分
        }

        return extractScoreFromResponse(response.getContent());
    }

    /**
     * 从响应内容中提取分数
     */
    private Double extractScoreFromResponse(String content) {
        if (content == null || content.trim().isEmpty()) {
            return 0.0;
        }

        // 清理内容，去除可能的空格和换行
        String cleanContent = content.trim();

        try {
            // 尝试直接解析为数字
            return Double.parseDouble(cleanContent);
        } catch (NumberFormatException e) {
            // 如果直接解析失败，使用正则表达式提取
            Matcher matcher = NUMBER_PATTERN.matcher(cleanContent);
            if (matcher.find()) {
                try {
                    return Double.parseDouble(matcher.group());
                } catch (NumberFormatException ex) {
                    log.warn("Failed to parse score from response: {}", cleanContent);
                }
            }
        }

        log.warn("No valid score found in response, returning default score 0.0. Response: {}", cleanContent);
        return 0.0;
    }
}