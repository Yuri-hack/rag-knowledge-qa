package io.github.yuri_hack.rag_knowledge_qa.vector.init;

import io.github.yuri_hack.rag_knowledge_qa.config.MilvusConfig;
import io.github.yuri_hack.rag_knowledge_qa.exception.VectorStorageException;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * milvus数据库初始化器
 * ！！！ 生产环境不建议代码创建 ！！！
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class MilvusVectorInitializer {

    private final MilvusServiceClient milvusClient;
    private final MilvusConfig milvusConfig;

    private static final String VECTOR_FIELD = "vector";
    private static final String DOCUMENT_ID_FIELD = "document_id";
    private static final String CHUNK_INDEX_FIELD = "chunk_index";
    private static final String CHUNK_ID_FIELD = "chunk_id";


    @PostConstruct
    public void init() {
        if (milvusConfig.isRecreate()) {
            // 删除重建
            recreateCollection();
        } else {
            initializeCollection();
        }
    }

    /**
     * 初始化集合
     */
    private void initializeCollection() {
        try {
            // 检查集合是否存在
            HasCollectionParam hasCollectionParam = HasCollectionParam.newBuilder()
                    .withCollectionName(milvusConfig.getCollectionName())
                    .build();
            R<Boolean> hasCollection = milvusClient.hasCollection(hasCollectionParam);

            if (!hasCollection.getData()) {
                createCollection();
                createIndex();
                log.info("Milvus集合创建完成: {}", milvusConfig.getCollectionName());
            } else {
                log.info("Milvus集合已存在: {}", milvusConfig.getCollectionName());
            }

            // 加载集合到内存
            loadCollection();

        } catch (Exception e) {
            log.error("初始化Milvus集合失败", e);
            throw new VectorStorageException("Milvus集合初始化失败", e);
        }
    }

    /**
     * 删除并重新创建集合
     */
    private void recreateCollection() {
        try {
            // 删除现有集合
            DropCollectionParam dropParam = DropCollectionParam.newBuilder()
                    .withCollectionName(milvusConfig.getCollectionName())
                    .build();
            milvusClient.dropCollection(dropParam);
            log.info("已删除集合: {}", milvusConfig.getCollectionName());

            // 重新初始化
            initializeCollection();
            log.info("已重新创建集合: {}", milvusConfig.getCollectionName());
        } catch (Exception e) {
            log.error("重新创建集合失败", e);
            throw new VectorStorageException("重新创建集合失败", e);
        }
    }

    /**
     * 创建集合
     */
    private void createCollection() {
        try {
            // 定义字段
            FieldType vectorField = FieldType.newBuilder()
                    .withName(VECTOR_FIELD)
                    .withDataType(DataType.FloatVector)
                    .withDimension(milvusConfig.getVectorDimension())
                    .build();

            FieldType documentIdField = FieldType.newBuilder()
                    .withName(DOCUMENT_ID_FIELD)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(64)
                    .build();

            FieldType chunkIndexField = FieldType.newBuilder()
                    .withName(CHUNK_INDEX_FIELD)
                    .withDataType(DataType.Int32)
                    .build();

            FieldType chunkIdField = FieldType.newBuilder()
                    .withName(CHUNK_ID_FIELD)
                    .withDataType(DataType.Int64) // 对应 DocumentChunk 的 Long id
                    .withPrimaryKey(true)
                    .withAutoID(false)
                    .build();

            // 创建集合参数
            CreateCollectionParam createCollectionParam = CreateCollectionParam.newBuilder()
                    .withCollectionName(milvusConfig.getCollectionName())
                    .withDescription("知识库文档块向量存储")
                    .withShardsNum(2)
                    .addFieldType(vectorField)
                    .addFieldType(documentIdField)
                    .addFieldType(chunkIndexField)
                    .addFieldType(chunkIdField)
                    .build();

            R<RpcStatus> response = milvusClient.createCollection(createCollectionParam);

            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new VectorStorageException("创建集合失败: " + response.getMessage());
            }

        } catch (Exception e) {
            log.error("创建Milvus集合失败", e);
            throw new VectorStorageException("创建集合失败", e);
        }
    }

    /**
     * 创建索引
     */
    private void createIndex() {
        try {
            IndexType indexType = IndexType.IVF_FLAT;
            String indexParam = "{\"nlist\":%d}".formatted(milvusConfig.getVectorDimension());

            CreateIndexParam createIndexParam = CreateIndexParam.newBuilder()
                    .withCollectionName(milvusConfig.getCollectionName())
                    .withFieldName(VECTOR_FIELD)
                    .withIndexType(indexType)
                    .withMetricType(MetricType.COSINE)
                    .withExtraParam(indexParam)
                    .withSyncMode(Boolean.TRUE)
                    .build();

            R<RpcStatus> response = milvusClient.createIndex(createIndexParam);

            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new VectorStorageException("创建索引失败: " + response.getMessage());
            }

        } catch (Exception e) {
            log.error("创建Milvus索引失败", e);
            throw new VectorStorageException("创建索引失败", e);
        }
    }

    /**
     * 加载集合到内存
     */
    private void loadCollection() {
        try {
            LoadCollectionParam loadCollectionParam = LoadCollectionParam.newBuilder()
                    .withCollectionName(milvusConfig.getCollectionName())
                    .build();

            R<RpcStatus> response = milvusClient.loadCollection(loadCollectionParam);

            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new VectorStorageException("加载集合失败: " + response.getMessage());
            }

        } catch (Exception e) {
            log.error("加载Milvus集合失败", e);
            throw new VectorStorageException("加载集合失败", e);
        }
    }

}
