package io.github.yuri_hack.rag_knowledge_qa.agent;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolFunction;
import com.alibaba.dashscope.utils.JsonUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：收集所有 Tool Bean，负责
 * ① 转成 DashScope 的 tools schema 列表
 * ② 按名字执行工具（参数解析 + required 校验也在这一层），把异常统一转成"失败文本"回传（失败也是输入，不炸链）
 */
@Component
public class ToolRegistry {

    /** 工具执行失败的约定前缀：调用方（AgentLoop/评测）依赖它判定工具是否失败 */
    public static final String TOOL_FAILURE_MARKER = "[工具执行失败] ";
    public static final String UNKNOWN_TOOL_MARKER = "[未知工具] ";

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> toolBeans) {
        for (Tool tool : toolBeans) {
            if (tools.put(tool.name(), tool) != null) {
                throw new IllegalStateException("存在重名工具: " + tool.name());
            }
        }
    }

    public List<ToolBase> toToolFunctions() {
        List<ToolBase> toolFunctions = new ArrayList<>();
        for (Tool tool : tools.values()) {
            FunctionDefinition definition = FunctionDefinition.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .parameters(tool.parametersSchema())
                    .build();
            toolFunctions.add(ToolFunction.builder().function(definition).build());
        }
        return toolFunctions;
    }

    /** 判定工具结果是否为失败文本（未注册工具 / 执行失败）。 */
    public static boolean isFailure(String toolResult) {
        return toolResult != null
                && (toolResult.startsWith(TOOL_FAILURE_MARKER) || toolResult.startsWith(UNKNOWN_TOOL_MARKER));
    }

    /** 接收模型给出的原始参数 JSON 串，解析失败同样按"工具失败"处理 */
    public String execute(String name, String argumentsJson) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return UNKNOWN_TOOL_MARKER + name;
        }
        JsonObject arguments;
        try {
            arguments = JsonUtils.parseString(argumentsJson).getAsJsonObject();
        } catch (Exception e) {
            return TOOL_FAILURE_MARKER + "参数不是合法 JSON: " + argumentsJson;
        }
        // 按 schema 的 required 声明先做存在性校验：与其让工具内部 NPE
        // （模型只会收到 "NullPointerException: null"，无从修正），不如明确告知缺哪个参数
        String missing = firstMissingRequired(tool, arguments);
        if (missing != null) {
            return TOOL_FAILURE_MARKER + "缺少必填参数 " + missing + "（收到: " + argumentsJson + "）";
        }
        try {
            return tool.execute(arguments);
        } catch (Exception e) {
            return TOOL_FAILURE_MARKER + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static String firstMissingRequired(Tool tool, JsonObject arguments) {
        JsonElement required = tool.parametersSchema().get("required");
        if (required == null || !required.isJsonArray()) {
            return null;
        }
        for (JsonElement requiredName : required.getAsJsonArray()) {
            String key = requiredName.getAsString();
            JsonElement value = arguments.get(key);
            if (value == null || value.isJsonNull()
                    || (value.isJsonPrimitive() && value.getAsString().isBlank())) {
                return key;
            }
        }
        return null;
    }
}
