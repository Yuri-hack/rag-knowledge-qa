package io.github.yuri_hack.rag_knowledge_qa.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageInfo {
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
}