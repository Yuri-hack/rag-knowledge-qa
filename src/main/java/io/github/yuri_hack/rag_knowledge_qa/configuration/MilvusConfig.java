package io.github.yuri_hack.rag_knowledge_qa.configuration;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MilvusConfig {
    
    @Value("${milvus.host:localhost}")
    private String host;
    
    @Value("${milvus.port:19530}")
    private Integer port;
    
    @Bean
    public MilvusServiceClient milvusServiceClient() {
        ConnectParam connectParam = ConnectParam.newBuilder()
            .withHost(host)
            .withPort(port)
            .build();
        return new MilvusServiceClient(connectParam);
    }
}