package io.github.yuri_hack.rag_knowledge_qa.controller;

import io.github.yuri_hack.rag_knowledge_qa.service.MilvusHealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequiredArgsConstructor
@RequestMapping("/healthy")
public class HealthyCheckController {

    private final MilvusHealthService milvusHealthService;

    private final RedisTemplate<String, String> redisTemplate;

    @GetMapping("/check")
    public Boolean check() {
        return isRedisConnected() && isMilvusConnected();
    }

    public boolean isRedisConnected() {
        try {
            // 执行ping命令，如果连接正常，会返回"PONG"
            String result = Objects.requireNonNull(redisTemplate.getConnectionFactory())
                    .getConnection().ping();
            return "PONG".equals(result);
        } catch (Exception e) {
            // 出现异常说明连接失败
            return false;
        }
    }

    private Boolean isMilvusConnected() {
        return milvusHealthService.isConnected();
    }

}