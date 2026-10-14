package io.github.d3kstra.rag_knowledge_qa.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "tongyi")
@Data
public class TongYiBaseConfig {
    private String apiKey;
    private TongYiModelConfig ragModelConfig;
    private TongYiModelConfig commonModelConfig;
    private TongYiModelConfig intentModelConfig;
    private TongYiModelConfig normalizeModelConfig;
    private TongYiModelConfig adaptiveModelConfig;
    private TongYiModelConfig rewriteModelConfig;
    /** WebSearchTool 子调用专用：enableSearch 必须为 true（与 tool_calls 互斥，只能单发使用） */
    private TongYiModelConfig webSearchModelConfig;
}