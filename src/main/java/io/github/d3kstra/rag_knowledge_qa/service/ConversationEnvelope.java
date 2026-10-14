package io.github.d3kstra.rag_knowledge_qa.service;

import io.github.d3kstra.rag_knowledge_qa.service.ChatSessionStore.Turn;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话信封：把"多轮上下文"的两段逻辑收敛到一处，供老管线编排层与 agent 端点共用。
 *
 * 之前两处各写一份（ChatOrchestrationServiceImpl 与 AgentChatController），
 * 语义容易漂移（改写触发条件、追加容错策略、存原始问题还是改写后问题）。
 *
 * 约定（与 D6 验收时一致）：
 * - 有会话、有历史、且 rewrite=true 时才改写；改写结果只用于检索/作答；
 * - 写入会话的始终是**原始问题**（避免多轮累积漂移）；
 * - 读写失败一律降级（无记忆），不炸主链路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationEnvelope {

    private final ChatSessionStore sessionStore;
    private final QueryRewriteService queryRewriteService;

    /** 会话是否可用（无 sessionId 视为单轮） */
    public static boolean hasSession(String sessionId) {
        return sessionId != null && !sessionId.isBlank();
    }

    /**
     * 载入历史并按需改写为自包含检索查询；无会话、首轮、或关闭改写时返回原问题。
     *
     * @param source 调用方标识（日志用：baseline / agent）
     */
    public String prepareQuery(String source, String question, String sessionId, boolean rewriteEnabled) {
        if (!hasSession(sessionId)) {
            return question;
        }
        List<Turn> history = sessionStore.load(sessionId);
        String effectiveQuery = question;
        if (!history.isEmpty() && rewriteEnabled) {
            effectiveQuery = queryRewriteService.rewrite(question, history);
        }
        log.info("{} 多轮: sessionId={}, 历史{}轮, 问题=[{}] → 检索query=[{}]",
                source, sessionId, history.size(), question, effectiveQuery);
        return effectiveQuery;
    }

    /** 追加一轮会话（记原始问题）；失败只记日志，不影响已生成的回答 */
    public void appendTurn(String sessionId, String rawQuestion, String answer) {
        if (!hasSession(sessionId)) {
            return;
        }
        try {
            sessionStore.append(sessionId, rawQuestion, answer);
        } catch (Exception e) {
            log.warn("会话追加失败: sessionId={}", sessionId, e);
        }
    }
}
