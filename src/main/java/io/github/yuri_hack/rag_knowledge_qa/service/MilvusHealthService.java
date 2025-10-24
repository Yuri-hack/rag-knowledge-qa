package io.github.yuri_hack.rag_knowledge_qa.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.collection.HasCollectionParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class MilvusHealthService {

    private final MilvusServiceClient milvusClient;

    public boolean isConnected() {
        try {
            // 尝试列出集合，或者检查一个不存在的集合，通过响应判断连接状态
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
}