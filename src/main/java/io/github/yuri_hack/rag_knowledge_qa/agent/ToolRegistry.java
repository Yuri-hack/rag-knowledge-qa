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

/** 工具注册表：schema 转换 + 按名执行（含 required 校验），异常统一转失败文本回传。 */
@Component
public class ToolRegistry {

    /** 失败约定前缀，供调用方判定 */
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
        // required 前置校验：明确告知缺参，优于 NPE 失败文本
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
