package io.github.yuri_hack.rag_knowledge_qa.configuration;

import lombok.Data;

@Data
public class TongYiModelConfig {
    private String model;
    private Integer maxTokens;
    private Float temperature;
    private Double topP;
    private Boolean enableSearch;
}