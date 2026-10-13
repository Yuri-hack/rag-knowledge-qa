package io.github.yuri_hack.rag_knowledge_qa.agent;

import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.tools.ToolBase;
import io.github.yuri_hack.rag_knowledge_qa.config.PromptConfig;
import io.github.yuri_hack.rag_knowledge_qa.config.TongYiBaseConfig;
import io.github.yuri_hack.rag_knowledge_qa.service.base.AskResult;
import io.github.yuri_hack.rag_knowledge_qa.service.base.BaseTongYiService;
import org.springframework.stereotype.Service;

import java.util.List;

/** BaseTongYiService 的 agent 子类：LLM 调用收口在基类。 */
@Service
public class TongYiAgentService extends BaseTongYiService {

    public TongYiAgentService(TongYiBaseConfig tongYiBaseConfig, PromptConfig promptConfig) {
        super(tongYiBaseConfig, promptConfig);
    }

    /** 阻塞调用 + 工具 schema，默认 ragModelConfig */
    public AskResult ask(List<Message> messages, List<ToolBase> tools) {
        return ask(messages, tools, tongYiBaseConfig.getRagModelConfig());
    }
}
