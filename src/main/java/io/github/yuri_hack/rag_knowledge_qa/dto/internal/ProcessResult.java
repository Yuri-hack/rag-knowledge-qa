package io.github.yuri_hack.rag_knowledge_qa.dto.internal;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class ProcessResult {
    private String content;
    private Map<String, Object> metadata;
}
