package io.github.yuri_hack.rag_knowledge_qa.service.base;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.aigc.generation.GenerationUsage;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import io.github.yuri_hack.rag_knowledge_qa.configuration.PromptConfig;
import io.github.yuri_hack.rag_knowledge_qa.configuration.TongYiBaseConfig;
import io.github.yuri_hack.rag_knowledge_qa.configuration.TongYiModelConfig;
import io.github.yuri_hack.rag_knowledge_qa.dto.ChatResponse;
import io.github.yuri_hack.rag_knowledge_qa.dto.StreamChatResponse;
import io.github.yuri_hack.rag_knowledge_qa.dto.UsageInfo;
import io.reactivex.Flowable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public abstract class BaseTongYiService {

    protected final TongYiBaseConfig tongYiBaseConfig;
    protected final PromptConfig promptConfig;
    protected final Generation generation = new Generation();

    /**
     * 通用的非流式生成方法
     */
    protected ChatResponse generate(List<Message> messages, TongYiModelConfig modelConfig) {
        GenerationParam param = buildGenerationParam(messages, modelConfig, false);
        try {
            GenerationResult generationResult = generation.call(param);
            return parseResponse(generationResult);
        } catch (Exception e) {
            log.error("调用通义千问API失败", e);
            return ChatResponse.builder()
                    .success(false)
                    .errorMessage("服务调用失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 通用的流式生成方法
     */
    protected Flux<StreamChatResponse> generateStream(List<Message> messages, TongYiModelConfig modelConfig) {
        try {
            GenerationParam param = buildGenerationParam(messages, modelConfig, true);
            Flowable<GenerationResult> flowable = generation.streamCall(param);

            return Flux.from(flowable)
                    .map(this::convertToStreamResponse)
                    .onErrorResume(throwable -> {
                        log.error("流式调用失败", throwable);
                        return Flux.just(createErrorResponse("调用失败: " + throwable.getMessage()));
                    });

        } catch (Exception e) {
            log.error("构建流式请求失败", e);
            return Flux.just(createErrorResponse("构建请求失败: " + e.getMessage()));
        }
    }

    /**
     * 构建消息列表
     */
    protected List<Message> buildMessages(String systemPrompt, String userMessage) {
        return Arrays.asList(
                Message.builder()
                        .role(Role.SYSTEM.getValue())
                        .content(systemPrompt)
                        .build(),
                Message.builder()
                        .role(Role.USER.getValue())
                        .content(userMessage)
                        .build()
        );
    }

    /**
     * 构建生成参数
     */
    protected GenerationParam buildGenerationParam(List<Message> messages, TongYiModelConfig modelConfig, boolean isStream) {
        return GenerationParam.builder()
                .apiKey(tongYiBaseConfig.getApiKey())
                .model(modelConfig.getModel())
                .messages(messages)
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .maxTokens(modelConfig.getMaxTokens())
                .temperature(modelConfig.getTemperature())
                .incrementalOutput(isStream)
                .topP(modelConfig.getTopP())
                .enableSearch(modelConfig.getEnableSearch())
                .build();
    }

    /**
     * 解析响应
     */
    protected ChatResponse parseResponse(GenerationResult result) {
        String content = result.getOutput().getChoices().get(0).getMessage().getContent();
        GenerationUsage usage = result.getUsage();

        UsageInfo usageInfo = UsageInfo.builder()
                .inputTokens(usage.getInputTokens())
                .outputTokens(usage.getOutputTokens())
                .totalTokens(usage.getTotalTokens())
                .build();

        return ChatResponse.builder()
                .success(true)
                .content(content)
                .usage(usageInfo)
                .build();
    }

    /**
     * 转换为流式响应
     */
    protected StreamChatResponse convertToStreamResponse(GenerationResult result) {
        UsageInfo usageInfo = null;
        String content = result.getOutput().getChoices().get(0).getMessage().getContent();
        String finishReason = result.getOutput().getChoices().get(0).getFinishReason();
        boolean finished = finishReason != null && !"null".equals(finishReason);

        if (finished) {
            usageInfo = UsageInfo.builder()
                    .inputTokens(result.getUsage().getInputTokens())
                    .outputTokens(result.getUsage().getOutputTokens())
                    .totalTokens(result.getUsage().getTotalTokens())
                    .build();
        }

        return StreamChatResponse.builder()
                .content(StringUtils.defaultString(content))
                .finished(finished)
                .usage(usageInfo)
                .build();
    }

    /**
     * 创建错误响应
     */
    protected StreamChatResponse createErrorResponse(String errorMessage) {
        return StreamChatResponse.builder()
                .content("")
                .finished(true)
                .errorMessage(errorMessage)
                .build();
    }
}