package io.github.yuri_hack.rag_knowledge_qa.cache.init;

import io.github.yuri_hack.rag_knowledge_qa.config.CacheConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 语义文档索引初始化
 * !!! 生产环境建议手动创建 !!!
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticDocumentCacheIndexInitializer {

    private final CacheConfig cacheConfig;

    @Value("${semantic.cache.index.dimension}")
    private Integer dimension;

    private final RedisConnectionFactory connectionFactory;

    @PostConstruct
    public void createIndexIfNotExists() {
        String indexName = cacheConfig.getSemantic().getIndexName();
        String cachePrefix = cacheConfig.getSemantic().getCachePrefix();
        try (RedisConnection conn = connectionFactory.getConnection()) {
            String[] args = {
                    indexName,
                    "ON", "HASH",
                    "PREFIX", "1", cachePrefix,
                    "SCHEMA",
                    "query", "TEXT",
                    "docIds", "TEXT",
                    "vector", "VECTOR", "HNSW", "6",
                    "TYPE", "FLOAT32",
                    "DIM", String.valueOf(dimension),
                    "DISTANCE_METRIC", "COSINE"
            };

            byte[][] binaryArgs = new byte[args.length][];
            for (int i = 0; i < args.length; i++) {
                binaryArgs[i] = args[i].getBytes(StandardCharsets.UTF_8);
            }

            conn.execute("FT.CREATE", binaryArgs);
            log.info("语义文档缓存 [{}] 创建成功", indexName);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getCause().getMessage().contains("Index already exists")) {
                log.info("语义文档缓存 [{}] 已经存在", indexName);
            } else {
                log.error("创建语义文档缓存失败", e);
            }
        }
    }
}