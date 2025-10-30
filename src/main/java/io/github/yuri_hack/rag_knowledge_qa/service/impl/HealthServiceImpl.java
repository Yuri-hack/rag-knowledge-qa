package io.github.yuri_hack.rag_knowledge_qa.service.impl;

import io.github.yuri_hack.rag_knowledge_qa.service.HealthService;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.collection.HasCollectionParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@Slf4j
@RequiredArgsConstructor
public class HealthServiceImpl implements HealthService {

    private final MilvusServiceClient milvusClient;

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public Boolean isMilvusConnected() {
        try {
            // 这里我们使用hasCollection来检查一个不存在的集合，如果连接正常，会返回一个包含状态码的响应
            R<Boolean> response = milvusClient.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName("non_existent_collection")
                    .build());

            // 如果响应状态为成功，说明连接正常
            return response.getStatus() == R.Status.Success.getCode();
        } catch (Exception e) {
            log.error("Milvus连接测试失败", e);
            return false;
        }
    }

    @Override
    public Boolean isRedisConnected() {
        try {
            // 执行ping命令，如果连接正常，会返回"PONG"
            String result = Objects.requireNonNull(redisTemplate.getConnectionFactory())
                    .getConnection().ping();
            return "PONG".equals(result);
        } catch (Exception e) {
            log.error("redis connect error", e);
            // 出现异常说明连接失败
            return false;
        }
    }

}