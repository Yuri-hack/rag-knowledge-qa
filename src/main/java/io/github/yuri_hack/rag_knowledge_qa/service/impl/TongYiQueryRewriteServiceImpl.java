package io.github.yuri_hack.rag_knowledge_qa.service.impl;

import com.alibaba.dashscope.common.Message;
import io.github.yuri_hack.rag_knowledge_qa.config.PromptConfig;
import io.github.yuri_hack.rag_knowledge_qa.config.TongYiBaseConfig;
import io.github.yuri_hack.rag_knowledge_qa.dto.response.ChatResponse;
import io.github.yuri_hack.rag_knowledge_qa.service.ChatSessionStore.Turn;
import io.github.yuri_hack.rag_knowledge_qa.service.QueryRewriteService;
import io.github.yuri_hack.rag_knowledge_qa.service.base.BaseTongYiService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class TongYiQueryRewriteServiceImpl extends BaseTongYiService implements QueryRewriteService {

    /** 历史答案进入改写上下文的截断长度：改写只需指代线索，不需要全文 */
    private static final int ANSWER_EXCERPT_LEN = 200;

    public TongYiQueryRewriteServiceImpl(TongYiBaseConfig tongYiBaseConfig, PromptConfig promptConfig) {
        super(tongYiBaseConfig, promptConfig);
    }

    @Override
    public String rewrite(String question, List<Turn> history) {
        if (history == null || history.isEmpty()) {
            return question;
        }
        try {
            StringBuilder transcript = new StringBuilder();
            for (Turn t : history) {
                transcript.append("用户：").append(t.question()).append('\n')
                        .append("助手：").append(StringUtils.abbreviate(t.answer(), ANSWER_EXCERPT_LEN)).append('\n');
            }
            List<Message> messages = buildMessages(
                    promptConfig.getRewritePrompt(),
                    transcript + "\n【最新问题】\n" + question);

            ChatResponse resp = generate(messages, tongYiBaseConfig.getRewriteModelConfig());
            if (!resp.isSuccess() || StringUtils.isBlank(resp.getContent())) {
                log.warn("query 改写失败，降级用原问题: {}", resp.getErrorMessage());
                return question;
            }
            String rewritten = unwrap(resp.getContent().trim());
            if (StringUtils.isBlank(rewritten)) {
                return question;
            }
            log.info("query 改写: [{}] → [{}]", question, rewritten);
            return rewritten;
        } catch (Exception e) {
            log.warn("query 改写异常，降级用原问题: [{}]", question, e);
            return question;
        }
    }

    /** 模型偶尔给答案包引号/前缀，剥掉最外层引号；其余情况原样信任 */
    private static String unwrap(String s) {
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("“") && s.endsWith("”")))) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }
}
