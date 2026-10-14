package io.github.d3kstra.rag_knowledge_qa.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
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

    /** Milvus 2.5 V2 客户端。URI 由可配置的 host/port 拼装，换容器只需改配置。 */
    @Bean(destroyMethod = "close")
    public MilvusClientV2 milvusClientV2() {
        String uri = "http://%s:%d".formatted(host, port);
        return new MilvusClientV2(ConnectConfig.builder().uri(uri).build());
    }
}
