import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationOutput.Choice;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.alibaba.dashscope.tools.ToolFunction;
import com.alibaba.dashscope.utils.JsonUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * D1 独立实验：调通第一次 tool call（不接项目、不接 Milvus）。
 *
 * 验证完整往返：
 *   发问 → 模型返回 toolCalls → 本地执行(模拟检索) → 结果以 tool 角色塞回 → 模型给最终答案
 *
 * 运行方式见 docs/prompt-changelog.md 同日记录。
 */
public class D1ToolCallExperiment {

  static final String MODEL = "qwen-plus";

  static final String SYSTEM_PROMPT =
      "你是云启科技的内部制度问答助手。回答制度问题前，必须先调用 search_knowledge 检索知识库，"
          + "并以检索结果为准；知识库没有的内容要明确说明。今天是 2026-10-05。";

  /** 模拟知识库检索（D2 接入真实的 KnowledgeBaseService.searchKnowledge）。 */
  static String searchKnowledge(String query) {
    System.out.println("    >>> 执行工具 search_knowledge(\"" + query + "\")");
    if (query.contains("年假") || query.contains("假期")) {
      return "【检索结果】《职级与假期对照表》(2026年版)：P4=5天，P5=8天，P6=10天，P7=12天；"
          + "司龄满3年+1天，满5年+2天，满10年+3天；年假合计每年不超过15天。";
    }
    return "【检索结果】未找到与「" + query + "」相关的内容。";
  }

  public static void main(String[] args) throws Exception {
    String apiKey = System.getenv("ALIYUN_API_KEY");

    // ① 定义工具：search_knowledge(query)
    FunctionDefinition fd = FunctionDefinition.builder()
        .name("search_knowledge")
        .description("检索公司内部制度知识库，返回相关制度条款。query 必须是自包含的检索关键词。")
        .parameters(JsonUtils.parseString(
            "{\"type\":\"object\",\"properties\":{"
                + "\"query\":{\"type\":\"string\",\"description\":\"自包含的检索关键词，如：P5 年假 天数\"}"
                + "},\"required\":[\"query\"]}").getAsJsonObject())
        .build();
    ToolFunction tool = ToolFunction.builder().function(fd).build();

    // ② 组装消息
    List<Message> messages = new ArrayList<>();
    messages.add(Message.builder().role(Role.SYSTEM.getValue()).content(SYSTEM_PROMPT).build());
    messages.add(Message.builder().role(Role.USER.getValue())
        .content("P5 员工的年假有几天？如果司龄满 5 年呢？").build());

    Generation gen = new Generation();

    // ③ 第一轮：看模型是否要调工具
    GenerationParam param = GenerationParam.builder()
        .apiKey(apiKey).model(MODEL)
        .messages(messages)
        .resultFormat(GenerationParam.ResultFormat.MESSAGE)
        .tools(List.of(tool))
        .build();

    GenerationResult r1 = gen.call(param);
    Message assistantMsg = r1.getOutput().getChoices().get(0).getMessage();
    System.out.println("=== 第 1 轮 finishReason=" + r1.getOutput().getChoices().get(0).getFinishReason()
        + " tokens=" + r1.getUsage().getTotalTokens() + " ===");

    if (assistantMsg.getToolCalls() == null || assistantMsg.getToolCalls().isEmpty()) {
      System.out.println("!! 模型没有调用工具，直接回答了（需调整 system prompt / 工具描述）：");
      System.out.println(assistantMsg.getContent());
      return;
    }

    // ④ 解析 toolCalls → 执行 → 以 tool 角色回传（关键认知：模型只"说"要调，执行权在这里）
    messages.add(assistantMsg); // 把"我要调工具"这条消息原样存进历史
    for (ToolCallBase tc : assistantMsg.getToolCalls()) {
      if (!"function".equals(tc.getType())) continue;
      ToolCallFunction fn = (ToolCallFunction) tc;
      String name = fn.getFunction().getName();
      String arguments = fn.getFunction().getArguments();
      System.out.println("    <<< 模型请求 tool_call id=" + tc.getId()
          + " name=" + name + " args=" + arguments);

      String result;
      try {
        String query = JsonUtils.parseString(arguments).getAsJsonObject().get("query").getAsString();
        result = searchKnowledge(query);
      } catch (Exception e) {
        result = "工具执行失败: " + e.getMessage(); // 失败也是输入，不抛异常
      }
      messages.add(Message.builder().role("tool").content(result).toolCallId(tc.getId()).build());
    }

    // ⑤ 第二轮：模型基于工具结果给最终答案
    param = GenerationParam.builder()
        .apiKey(apiKey).model(MODEL)
        .messages(messages)
        .resultFormat(GenerationParam.ResultFormat.MESSAGE)
        .tools(List.of(tool))
        .build();
    GenerationResult r2 = gen.call(param);
    System.out.println("=== 第 2 轮 tokens=" + r2.getUsage().getTotalTokens() + " ===");
    System.out.println("=== 最终答案 ===");
    System.out.println(r2.getOutput().getChoices().get(0).getMessage().getContent());
    System.out.println("=== 两轮总 tokens=" + (r1.getUsage().getTotalTokens() + r2.getUsage().getTotalTokens()) + " ===");

    // 打印完整轨迹，确认消息历史的形状（这就是 D2 循环要维护的状态）
    System.out.println("=== 消息历史形状 ===");
    for (Message m : messages) {
      String role = m.getRole();
      String preview = m.getContent() == null ? "(null)" :
          m.getContent().replaceAll("\\s+", " ").substring(0, Math.min(60, m.getContent().length()));
      System.out.println("  [" + role + (m.getToolCallId() != null ? " id=" + m.getToolCallId() : "") + "] "
          + preview + (m.getToolCalls() != null ? " toolCalls=" + m.getToolCalls().size() : ""));
    }
  }
}
