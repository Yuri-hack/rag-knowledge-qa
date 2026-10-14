package io.github.d3kstra.rag_knowledge_qa.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class StreamChatResponse {
    private String content;
    private boolean finished;
    private String errorMessage;
    private UsageInfo usage;

    /** prompt 版本（prompt 文本哈希，防止 prompt 漂移在数字层面不可见——BC02 教训） */
    private String promptVersion;
}