package io.github.yuri_hack.rag_knowledge_qa.agent;

import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.alibaba.dashscope.tools.ToolFunction;
import com.alibaba.dashscope.utils.JsonUtils;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * D1 Demo：真实知识库上的完整 tool call 往返（最小循环，D2 会抽出 AgentLoop）。
 * 运行：./mvnw spring-boot:run -Dspring-boot.run.arguments="--agent.demo=true --server.port=8081"
 */
@Component
@ConditionalOnProperty(value = "agent.demo", havingValue = "true")
public class AgentDemoRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentDemoRunner.class);

    /** v0.1，见 docs/prompt-changelog.md */
    private static final String SYSTEM_PROMPT =
            "你是云启科技的内部制度问答助手。回答制度问题前，必须先调用 search_knowledge 检索知识库，并以检索结果为准；"
                    + "知识库没有的内容要明确说明，不要编造。今天是 2026-10-05。";

    private static final int MAX_STEPS = 3;

    private final TongYiAgentService agentService;
    private final ToolRegistry toolRegistry;

    public AgentDemoRunner(TongYiAgentService agentService, ToolRegistry toolRegistry) {
        this.agentService = agentService;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public void run(String... args) {
        String question = "P5 员工的年假有几天？如果司龄满 5 年呢？";
        System.out.println("\n===== D1 DEMO =====");
        System.out.println("[Q] " + question);

        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().role(Role.SYSTEM.getValue()).content(SYSTEM_PROMPT).build());
        messages.add(Message.builder().role(Role.USER.getValue()).content(question).build());

        List<ToolBase> tools = toolRegistry.toToolFunctions();

        for (int step = 1; step <= MAX_STEPS; step++) {
            Message reply = agentService.ask(messages, tools);

            if (reply.getToolCalls() == null || reply.getToolCalls().isEmpty()) {
                System.out.println("\n===== 最终答案（第 " + step + " 步终止）=====");
                System.out.println(reply.getContent());
                return;
            }

            messages.add(reply); // "我要调工具"这条消息原样入历史
            for (ToolCallBase tc : reply.getToolCalls()) {
                if (!"function".equals(tc.getType())) continue;
                ToolCallFunction fn = (ToolCallFunction) tc;
                String fnName = fn.getFunction().getName();
                JsonObject fnArgs = JsonUtils.parseString(fn.getFunction().getArguments()).getAsJsonObject();
                System.out.println(">>> [step " + step + "] tool_call: " + fnName + " " + fnArgs);

                String result = toolRegistry.execute(fnName, fnArgs);
                String firstLine = result.split("\n")[0];
                System.out.println("<<< [step " + step + "] " + firstLine);

                messages.add(Message.builder().role("tool").content(result).toolCallId(tc.getId()).build());
            }
        }
        System.out.println("达到步数上限（" + MAX_STEPS + "），防绕圈终止");
        log.info("D1 demo finished");
    }
}
