package io.github.yuri_hack.rag_knowledge_qa.service.base;

import com.alibaba.dashscope.common.Message;
import io.github.yuri_hack.rag_knowledge_qa.dto.response.UsageInfo;

/**
 * ask() 的返回：assistant 原始消息 + 本次调用的 token 用量（AgentTrace 逐步记账的来源）。
 */
public record AskResult(Message message, UsageInfo usage) {
}
