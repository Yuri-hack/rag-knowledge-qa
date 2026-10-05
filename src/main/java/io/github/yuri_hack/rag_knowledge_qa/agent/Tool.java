package io.github.yuri_hack.rag_knowledge_qa.agent;

import com.google.gson.JsonObject;

/**
 * agent 工具的统一抽象。
 * D4 新工具（get_document_detail / 联网搜索）只需实现此接口并注册为 Spring Bean。
 */
public interface Tool {

    String name();

    /** 工具描述：措辞直接决定模型选不选、怎么调（见 docs/prompt-changelog.md）。 */
    String description();

    /** JSON Schema（gson JsonObject），声明参数结构。 */
    JsonObject parametersSchema();

    /** 执行工具。抛出的异常由 ToolRegistry 统一转成失败文本回传（失败也是输入）。 */
    String execute(JsonObject arguments) throws Exception;
}
