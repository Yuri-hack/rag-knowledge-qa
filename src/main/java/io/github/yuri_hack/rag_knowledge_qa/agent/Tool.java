package io.github.yuri_hack.rag_knowledge_qa.agent;

import com.google.gson.JsonObject;

/**
 * agent 工具的统一抽象。
 * D4 新工具（get_document_detail / 联网搜索）只需实现此接口并注册为 Spring Bean。
 */
public interface Tool {

    String name();

    /** 工具描述：措辞决定模型选不选、怎么调 */
    String description();

    /** JSON Schema（gson JsonObject），声明参数结构。 */
    JsonObject parametersSchema();

    /** 执行；异常由 ToolRegistry 转失败文本回传 */
    String execute(JsonObject arguments) throws Exception;
}
