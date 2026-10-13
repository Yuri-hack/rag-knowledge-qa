package io.github.yuri_hack.rag_knowledge_qa.service.base;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.aigc.generation.GenerationUsage;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.protocol.ConnectionOptions;
import com.alibaba.dashscope.protocol.Protocol;
import com.alibaba.dashscope.tools.ToolBase;
import io.github.yuri_hack.rag_knowledge_qa.config.PromptConfig;
import io.github.yuri_hack.rag_knowledge_qa.config.TongYiBaseConfig;
import io.github.yuri_hack.rag_knowledge_qa.config.TongYiModelConfig;
import io.github.yuri_hack.rag_knowledge_qa.dto.response.ChatResponse;
import io.github.yuri_hack.rag_knowledge_qa.dto.response.StreamChatResponse;
import io.github.yuri_hack.rag_knowledge_qa.dto.response.UsageInfo;
import io.github.yuri_hack.rag_knowledge_qa.util.ReactorUtils;
import io.reactivex.Flowable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
@RequiredArgsConstructor
public abstract class BaseTongYiService {

    protected final TongYiBaseConfig tongYiBaseConfig;
    protected final PromptConfig promptConfig;

    /** ask() 失败消息前缀，AgentLoop 依此区分模型故障与正常终止 */
    public static final String LLM_FAILURE_MARKER = "[模型调用失败] ";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(10);

    /** ask 失败重试一次，避免网络抖动混入评测 */
    private static final int MAX_ASK_ATTEMPTS = 2;

    /** 客户端级超时（ask 与流式共用）；readTimeout 按块间间隔生效 */
    protected final Generation generation = new Generation(
            Protocol.HTTP.getValue(),
            null,
            ConnectionOptions.builder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .readTimeout(READ_TIMEOUT)
                    .writeTimeout(WRITE_TIMEOUT)
                    .build());

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

    /** agent 阻塞调用：带工具 schema，返回原始 assistant 消息 */
    protected AskResult ask(List<Message> messages, List<ToolBase> tools, TongYiModelConfig modelConfig) {
        GenerationParam param = buildAskParam(messages, tools, modelConfig);
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ASK_ATTEMPTS; attempt++) {
            try {
                GenerationResult result = generation.call(param);
                GenerationUsage usage = result.getUsage();
                UsageInfo usageInfo = usage == null ? UsageInfo.builder().build() : toUsageInfo(usage);
                return new AskResult(result.getOutput().getChoices().get(0).getMessage(), usageInfo);
            } catch (Exception e) {
                lastError = e;
                log.warn("agent ask 第 {} 次调用失败: {}", attempt, e.getMessage());
            }
        }
        log.error("agent ask 调用失败（已重试 {} 次）", MAX_ASK_ATTEMPTS - 1, lastError);
        // 失败也是输入：返回一条失败消息，让循环决定重试还是终止（不抛异常炸链）
        return new AskResult(
                Message.builder()
                        .role(Role.ASSISTANT.getValue())
                        .content(LLM_FAILURE_MARKER + lastError.getMessage())
                        .build(),
                UsageInfo.builder().build());
    }

    private GenerationParam buildAskParam(List<Message> messages, List<ToolBase> tools, TongYiModelConfig modelConfig) {
        var builder = GenerationParam.builder()
                .apiKey(tongYiBaseConfig.getApiKey())
                .model(modelConfig.getModel())
                .messages(messages)
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .maxTokens(modelConfig.getMaxTokens())
                .temperature(modelConfig.getTemperature())
                .topP(modelConfig.getTopP())
                // 独立开关控制（默认 false），不挂 modelConfig 避免基线误开
                .enableSearch(Boolean.TRUE.equals(tongYiBaseConfig.getAgentEnableSearch()));
        // 工具列表为空时不发送 tools 字段（空数组部分端点会报错）
        if (tools != null && !tools.isEmpty()) {
            builder.tools(tools);
        }
        return builder.build();
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

    /** 缓存回写与 SSE 订阅者解耦，见 ReactorUtils#writeAnswerDetached */
    protected Flux<StreamChatResponse> writeCacheDetached(Flux<StreamChatResponse> source,
                                                          Consumer<String> cacheAction) {
        return ReactorUtils.writeAnswerDetached(source, cacheAction);
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

        return ChatResponse.builder()
                .success(true)
                .content(content)
                .usage(toUsageInfo(result.getUsage()))
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
            usageInfo = toUsageInfo(result.getUsage());
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

    private static UsageInfo toUsageInfo(GenerationUsage usage) {
        return UsageInfo.builder()
                .inputTokens(usage.getInputTokens())
                .outputTokens(usage.getOutputTokens())
                .totalTokens(usage.getTotalTokens())
                .build();
    }
}