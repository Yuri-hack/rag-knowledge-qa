package io.github.yuri_hack.rag_knowledge_qa.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "rag")
@Data
public class PromptConfig {

    private String ragPrompt;

    private String dailyPrompt;

    private String intentPrompt;

    private String normalizePrompt;
}