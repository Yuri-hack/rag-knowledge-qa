package io.github.yuri_hack.rag_knowledge_qa.agent;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolFunction;
import com.google.gson.JsonObject;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：收集所有 Tool Bean，负责
 * ① 转成 DashScope 的 tools schema 列表
 * ② 按名字执行工具，把异常统一转成"失败文本"回传（失败也是输入，不炸链）
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> toolBeans) {
        for (Tool t : toolBeans) {
            if (tools.put(t.name(), t) != null) {
                throw new IllegalStateException("存在重名工具: " + t.name());
            }
        }
    }

    public List<ToolBase> toToolFunctions() {
        List<ToolBase> list = new ArrayList<>();
        for (Tool t : tools.values()) {
            FunctionDefinition fd = FunctionDefinition.builder()
                    .name(t.name())
                    .description(t.description())
                    .parameters(t.parametersSchema())
                    .build();
            list.add(ToolFunction.builder().function(fd).build());
        }
        return list;
    }

    public String execute(String name, JsonObject arguments) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return "[未知工具] " + name;
        }
        try {
            return tool.execute(arguments);
        } catch (Exception e) {
            return "[工具执行失败] " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
