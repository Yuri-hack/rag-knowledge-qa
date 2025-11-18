package io.github.yuri_hack.rag_knowledge_qa.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "routing.threshold")
public class RoutingConfig {

    private double retrievalHigh;

    private double retrievalLow;

    private double intentHigh;

    private double intentLow;
}
