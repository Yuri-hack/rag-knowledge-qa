package io.github.yuri_hack.rag_knowledge_qa.agent;

import io.github.yuri_hack.rag_knowledge_qa.dto.response.UsageInfo;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 循环中的一步：一次 LLM 调用 + （可能有的）若干工具执行。
 */
@Getter
public class AgentStep {

    private final int step;
    private final UsageInfo usage;

    /** 本步模型发出的全部工具调用（size>1 即并行 tool call，D2 观察项） */
    private final List<ToolCallRecord> toolCalls = new ArrayList<>();

    /** 模型的文本输出；终止步为最终答案，工具步通常为 null */
    @Setter
    private String content;

    public AgentStep(int step, UsageInfo usage) {
        this.step = step;
        this.usage = usage;
    }

    public void addToolCall(String name, String arguments, String resultExcerpt, boolean success) {
        toolCalls.add(new ToolCallRecord(name, arguments, resultExcerpt, success));
    }

    @Getter
    @AllArgsConstructor
    public static class ToolCallRecord {
        private final String name;
        private final String arguments;
        private final String resultExcerpt;
        private final boolean success;
    }
}
