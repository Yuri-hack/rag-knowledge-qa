package io.github.yuri_hack.rag_knowledge_qa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;

import java.util.ArrayList;
import java.util.List;

/**
 * D6 会话记忆：sessionId → 最近 N 轮问答。
 * Redis list 存储，每个元素一轮（JSON），不做摘要压缩（路线 D6 决策）。
 * 读写失败一律降级为"无记忆"，不炸主链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionStore {

    /** 一轮对话：question 记录用户原始问题（非改写后），answer 为最终完整回答 */
    public record Turn(String question, String answer) {}

    private static final String KEY_PREFIX = "chat:session:";

    private final JedisPooled jedisPooled;
    private final ObjectMapper objectMapper;

    @Value("${rag.memory.max-turns:3}")
    private int maxTurns;

    @Value("${rag.memory.ttl-minutes:120}")
    private int ttlMinutes;

    public List<Turn> load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try {
            List<String> raw = jedisPooled.lrange(KEY_PREFIX + sessionId, 0, -1);
            List<Turn> turns = new ArrayList<>();
            for (String s : raw) {
                turns.add(objectMapper.readValue(s, Turn.class));
            }
            return turns;
        } catch (Exception e) {
            log.warn("会话读取失败（按无记忆处理）: sessionId={}", sessionId, e);
            return List.of();
        }
    }

    /** 追加一轮并裁剪到最近 N 轮，刷新 TTL。供 detached 车道调用，异常只记日志。 */
    public void append(String sessionId, String question, String answer) {
        if (sessionId == null || sessionId.isBlank() || answer == null || answer.isBlank()) {
            return;
        }
        try {
            String key = KEY_PREFIX + sessionId;
            jedisPooled.rpush(key, objectMapper.writeValueAsString(new Turn(question, answer)));
            if (maxTurns > 0) {
                jedisPooled.ltrim(key, -maxTurns, -1);
            }
            jedisPooled.expire(key, ttlMinutes * 60L);
        } catch (Exception e) {
            log.error("会话追加失败: sessionId={}", sessionId, e);
        }
    }

    /** 评测/调试用：清除指定会话 */
    public void delete(String sessionId) {
        try {
            jedisPooled.del(KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.warn("会话删除失败: sessionId={}", sessionId, e);
        }
    }
}
