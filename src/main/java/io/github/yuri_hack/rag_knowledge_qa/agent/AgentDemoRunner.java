package io.github.yuri_hack.rag_knowledge_qa.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * D2 Demo：AgentLoop 正式循环的三题验收
 * ① 单跳+计算 ② 范围定向（昨日发现的基线失败案例）③ 双主题（观察多步/并行 tool call）
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

    private static final int MAX_STEPS = 6;

    private static final String[] QUESTIONS = {
            "P5 员工的年假有几天？如果司龄满 5 年呢？",
            "上海子公司的 P5 员工年假是几天？",
            "P5 的年假天数和试用期病假上限分别是多少？"
    };

    private final AgentLoop agentLoop;

    public AgentDemoRunner(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    @Override
    public void run(String... args) {
        for (String question : QUESTIONS) {
            AgentTrace trace = agentLoop.run(question, SYSTEM_PROMPT, MAX_STEPS);
            System.out.println(trace.summary());
        }
        log.info("D2 demo finished");
    }
}
