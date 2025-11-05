package io.github.yuri_hack.rag_knowledge_qa.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
public class MilvusConfig {
    
    @Value("${milvus.host:localhost}")
    private String host;
    
    @Value("${milvus.port:19530}")
    private Integer port;

    @Value("${milvus.collection.name:knowledge_base}")
    private String collectionName;

    @Value("${milvus.collection.vector-dimension:1024}")
    private Integer vectorDimension;

    @Value("${milvus.collection.partitions:default}")
    private List<String> partitions;

    @Value("${milvus.collection.recreate:false}")
    private boolean recreate;
    
    @Bean
    public MilvusServiceClient milvusServiceClient() {
        ConnectParam connectParam = ConnectParam.newBuilder()
            .withHost(host)
            .withPort(port)
            .build();
        return new MilvusServiceClient(connectParam);
    }
}