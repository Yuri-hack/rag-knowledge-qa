package io.github.yuri_hack.rag_knowledge_qa.agent;

import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.tools.ToolBase;
import io.github.yuri_hack.rag_knowledge_qa.config.PromptConfig;
import io.github.yuri_hack.rag_knowledge_qa.config.TongYiBaseConfig;
import io.github.yuri_hack.rag_knowledge_qa.service.base.BaseTongYiService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * BaseTongYiService 的 agent 用具体子类：
 * LLM 调用仍收口在基类（项目事实：generate/generateStream/ask 唯一出入口）。
 */
@Service
public class TongYiAgentService extends BaseTongYiService {

    public TongYiAgentService(TongYiBaseConfig tongYiBaseConfig, PromptConfig promptConfig) {
        super(tongYiBaseConfig, promptConfig);
    }

    /** agent 循环专用：阻塞调用 + 工具 schema，默认用 ragModelConfig */
    public Message ask(List<Message> messages, List<ToolBase> tools) {
        return ask(messages, tools, tongYiBaseConfig.getRagModelConfig());
    }
}
