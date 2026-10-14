package io.github.d3kstra.rag_knowledge_qa.config;

import lombok.Data;

@Data
public class TongYiModelConfig {
    private String model;
    private Integer maxTokens;
    private Float temperature;
    private Double topP;
    private Boolean enableSearch;
}