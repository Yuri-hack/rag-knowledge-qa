package io.github.yuri_hack.rag_knowledge_qa.configuration;

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
}