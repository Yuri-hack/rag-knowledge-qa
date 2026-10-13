package io.github.yuri_hack.rag_knowledge_qa.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * AgentLoop 三题验收 demo。
 * 运行：./mvnw spring-boot:run -Dspring-boot.run.arguments="--agent.demo=true --server.port=8081"
 */
@Component
@ConditionalOnProperty(value = "agent.demo", havingValue = "true")
public class AgentDemoRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentDemoRunner.class);

    /** v0.3 无改善且成本翻倍，回滚 v0.2.1（见 BC02） */
    private static final String SYSTEM_PROMPT = AgentPrompts.SYSTEM_V02;

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
