package io.github.yuri_hack.rag_knowledge_qa.agent;

import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.tools.ToolBase;
import io.github.yuri_hack.rag_knowledge_qa.config.PromptConfig;
import io.github.yuri_hack.rag_knowledge_qa.config.TongYiBaseConfig;
import io.github.yuri_hack.rag_knowledge_qa.dto.response.ChatResponse;
import io.github.yuri_hack.rag_knowledge_qa.service.base.AskResult;
import io.github.yuri_hack.rag_knowledge_qa.service.base.BaseTongYiService;
import org.springframework.stereotype.Service;

import java.util.List;

/** BaseTongYiService 的 agent 子类：LLM 调用收口在基类。 */
@Service
public class TongYiAgentService extends BaseTongYiService {

    /** WebSearchTool 子调用的 system prompt：约束只答该问题、搜不到如实说，防止摘要膨胀回灌主循环 */
    private static final String WEB_SEARCH_SYSTEM_PROMPT =
            "你是联网搜索助手。回答时会自动获取互联网实时搜索结果；"
            + "基于搜索结果直接、简洁地回答用户问题，不要展开无关内容；搜索不到相关信息时如实说明。";

    public TongYiAgentService(TongYiBaseConfig tongYiBaseConfig, PromptConfig promptConfig) {
        super(tongYiBaseConfig, promptConfig);
    }

    /** 阻塞调用 + 工具 schema，默认 ragModelConfig */
    public AskResult ask(List<Message> messages, List<ToolBase> tools) {
        return ask(messages, tools, tongYiBaseConfig.getRagModelConfig());
    }

    /**
     * WebSearchTool 的联网搜索子调用：单发、无 tools、走 webSearchModelConfig（enable-search=true）。
     * enableSearch 与 tool_calls 在同一请求互斥（静默失效，见 56b0124 实验），故只在工具内部这样用。
     */
    public ChatResponse searchWeb(String query) {
        return generate(List.of(
                Message.builder().role(Role.SYSTEM.getValue()).content(WEB_SEARCH_SYSTEM_PROMPT).build(),
                Message.builder().role(Role.USER.getValue()).content(query).build()),
                tongYiBaseConfig.getWebSearchModelConfig());
    }
}
