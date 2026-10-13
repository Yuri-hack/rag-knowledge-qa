package io.github.yuri_hack.rag_knowledge_qa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;

import java.util.ArrayList;
import java.util.List;

/** D6 会话记忆：Redis list 存最近 N 轮，读写失败降级为无记忆。 */
@Slf4j
@Service
public class ChatSessionStore {

    /** 一轮对话：question 记录用户原始问题（非改写后），answer 为最终完整回答 */
    public record Turn(String question, String answer) {}

    private static final String KEY_PREFIX = "chat:session:";
    private static final long SECONDS_PER_MINUTE = 60L;

    private final JedisPooled jedisPooled;
    private final ObjectMapper objectMapper;
    private final int maxTurns;
    private final int ttlMinutes;

    /** 构造注入配置，字段保持不可变 */
    public ChatSessionStore(JedisPooled jedisPooled, ObjectMapper objectMapper,
                            @Value("${rag.memory.max-turns:3}") int maxTurns,
                            @Value("${rag.memory.ttl-minutes:120}") int ttlMinutes) {
        this.jedisPooled = jedisPooled;
        this.objectMapper = objectMapper;
        this.maxTurns = maxTurns;
        this.ttlMinutes = ttlMinutes;
    }

    public List<Turn> load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try {
            List<String> raw = jedisPooled.lrange(keyOf(sessionId), 0, -1);
            List<Turn> turns = new ArrayList<>();
            for (String turnJson : raw) {
                turns.add(objectMapper.readValue(turnJson, Turn.class));
            }
            return turns;
        } catch (Exception e) {
            log.warn("会话读取失败（按无记忆处理）: sessionId={}", sessionId, e);
            return List.of();
        }
    }

    /** 追加一轮并裁剪到 N 轮，刷新 TTL */
    public void append(String sessionId, String question, String answer) {
        if (sessionId == null || sessionId.isBlank() || answer == null || answer.isBlank()) {
            return;
        }
        try {
            String key = keyOf(sessionId);
            jedisPooled.rpush(key, objectMapper.writeValueAsString(new Turn(question, answer)));
            if (maxTurns > 0) {
                jedisPooled.ltrim(key, -maxTurns, -1);
            }
            jedisPooled.expire(key, ttlMinutes * SECONDS_PER_MINUTE);
        } catch (Exception e) {
            log.error("会话追加失败: sessionId={}", sessionId, e);
        }
    }

    /** 评测/调试用：清除指定会话 */
    public void delete(String sessionId) {
        try {
            jedisPooled.del(keyOf(sessionId));
        } catch (Exception e) {
            log.warn("会话删除失败: sessionId={}", sessionId, e);
        }
    }

    private static String keyOf(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
