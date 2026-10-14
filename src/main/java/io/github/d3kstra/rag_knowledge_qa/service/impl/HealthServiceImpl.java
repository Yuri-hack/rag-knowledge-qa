package io.github.d3kstra.rag_knowledge_qa.service.impl;

import io.github.d3kstra.rag_knowledge_qa.config.MilvusConfig;
import io.github.d3kstra.rag_knowledge_qa.service.HealthService;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@Slf4j
@RequiredArgsConstructor
public class HealthServiceImpl implements HealthService {

    private final MilvusClientV2 milvusClient;

    private final MilvusConfig milvusConfig;

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public Boolean isMilvusConnected() {
        try {
            // describeCollection 成功即说明连接正常且目标集合存在（V2 下没有 R 状态码包装，异常即失败）
            milvusClient.describeCollection(DescribeCollectionReq.builder()
                    .collectionName(milvusConfig.getCollectionName())
                    .build());
            return true;
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
