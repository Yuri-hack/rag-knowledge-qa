package io.github.yuri_hack.rag_knowledge_qa.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamChatResponse {
    private String content;
    private boolean finished;
    private String errorMessage;
    private UsageInfo usage;
}