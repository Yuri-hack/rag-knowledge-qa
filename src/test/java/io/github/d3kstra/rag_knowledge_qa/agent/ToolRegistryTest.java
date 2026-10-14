package io.github.d3kstra.rag_knowledge_qa.agent;

import com.alibaba.dashscope.utils.JsonUtils;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 工具注册表：schema 转换、必填校验、失败文本协议（"失败也是输入"）。 */
class ToolRegistryTest {

    private static Tool tool(String name, boolean throwOnExecute) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "测试工具 " + name;
            }

            @Override
            public JsonObject parametersSchema() {
                return JsonUtils.parseString("{\"type\":\"object\",\"properties\":{"
                        + "\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}").getAsJsonObject();
            }

            @Override
            public String execute(JsonObject arguments) {
                if (throwOnExecute) {
                    throw new IllegalStateException("模拟执行失败");
                }
                return "OK:" + arguments.get("query").getAsString();
            }
        };
    }

    @Test
    void 注册表拒绝重名工具() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(tool("dup", false), tool("dup", false))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重名");
    }

    @Test
    void 未注册工具返回未知工具标记() {
        ToolRegistry registry = new ToolRegistry(List.of(tool("a", false)));
        String result = registry.execute("nope", "{}");
        assertThat(result).startsWith(ToolRegistry.UNKNOWN_TOOL_MARKER);
        assertThat(ToolRegistry.isFailure(result)).isTrue();
    }

    @Test
    void 非法JSON按失败返回且不抛异常() {
        ToolRegistry registry = new ToolRegistry(List.of(tool("a", false)));
        assertThat(registry.execute("a", "not-json")).startsWith(ToolRegistry.TOOL_FAILURE_MARKER);
    }

    @Test
    void 缺少必填参数时明确告知参数名() {
        ToolRegistry registry = new ToolRegistry(List.of(tool("a", false)));
        String result = registry.execute("a", "{}");
        assertThat(result).contains("缺少必填参数 query");
        assertThat(result).doesNotContain("NullPointerException");
    }

    @Test
    void 空白值等同缺失() {
        ToolRegistry registry = new ToolRegistry(List.of(tool("a", false)));
        assertThat(registry.execute("a", "{\"query\":\"  \"}")).contains("缺少必填参数 query");
    }

    @Test
    void 工具内部异常转失败文本() {
        ToolRegistry registry = new ToolRegistry(List.of(tool("a", true)));
        String result = registry.execute("a", "{\"query\":\"x\"}");
        assertThat(result).startsWith(ToolRegistry.TOOL_FAILURE_MARKER).contains("模拟执行失败");
        assertThat(ToolRegistry.isFailure(result)).isTrue();
    }

    @Test
    void 正常结果不被判为失败() {
        ToolRegistry registry = new ToolRegistry(List.of(tool("a", false)));
        String result = registry.execute("a", "{\"query\":\"x\"}");
        assertThat(result).isEqualTo("OK:x");
        assertThat(ToolRegistry.isFailure(result)).isFalse();
    }

    @Test
    void schema转换为dashscope工具定义() {
        ToolRegistry registry = new ToolRegistry(List.of(tool("a", false)));
        assertThat(registry.toToolFunctions()).hasSize(1);
        assertThat(registry.toToolFunctions().get(0)).isNotNull();
    }
}
