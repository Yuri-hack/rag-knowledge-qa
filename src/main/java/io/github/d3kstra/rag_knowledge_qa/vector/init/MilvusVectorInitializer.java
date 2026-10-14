package io.github.d3kstra.rag_knowledge_qa.vector.init;

import io.github.d3kstra.rag_knowledge_qa.config.MilvusConfig;
import io.github.d3kstra.rag_knowledge_qa.exception.VectorStorageException;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * milvus数据库初始化器（Milvus 2.5 MilvusClientV2）
 * ！！！ 生产环境不建议代码创建 ！！！
 *
 * schema 在原有字段（chunk_id 主键 / vector / document_id / chunk_index）基础上新增：
 *  - text：VARCHAR，开启 chinese analyzer（中文子词召回的决定变量，见 docs/milvus-2.5-upgrade-checklist.md）；
 *  - sparse：SPARSE_FLOAT_VECTOR，由 BM25 Function 从 text 生成；
 * 索引：vector → HNSW/IP（嵌入已 L2 归一化，IP 等价余弦）；sparse → SPARSE_INVERTED_INDEX/BM25。
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class MilvusVectorInitializer {

    private final MilvusClientV2 milvusClient;
    private final MilvusConfig milvusConfig;

    private static final String VECTOR_FIELD = "vector";
    private static final String DOCUMENT_ID_FIELD = "document_id";
    private static final String CHUNK_INDEX_FIELD = "chunk_index";
    private static final String CHUNK_ID_FIELD = "chunk_id";
    private static final String TEXT_FIELD = "text";
    private static final String SPARSE_FIELD = "sparse";
    private static final String BM25_FUNCTION = "bm25_text";
    /** 业务编号（资产编号/单号/型号代码）数组字段：BM25 分词对 YC-XX-YYYY-NNNN 失效，靠它做精确过滤 */
    private static final String CODES_FIELD = "codes";

    /** 分块上限 512 字符，留余量 */
    private static final int TEXT_MAX_LENGTH = 4096;
    /** 单个业务编号最大长度与每块最大编号数（与 MilvusVectorServiceImpl.extractCodes 的上限一致） */
    private static final int CODE_MAX_LENGTH = 64;
    private static final int CODE_MAX_CAPACITY = 8;

    @PostConstruct
    public void init() {
        if (milvusConfig.isRecreate()) {
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
            Boolean exists = milvusClient.hasCollection(HasCollectionReq.builder()
                    .collectionName(milvusConfig.getCollectionName())
                    .build());

            if (!Boolean.TRUE.equals(exists)) {
                createCollection();
                log.info("Milvus集合创建完成: {}", milvusConfig.getCollectionName());
            } else {
                log.info("Milvus集合已存在: {}", milvusConfig.getCollectionName());
            }

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
            milvusClient.dropCollection(DropCollectionReq.builder()
                    .collectionName(milvusConfig.getCollectionName())
                    .build());
            log.info("已删除集合: {}", milvusConfig.getCollectionName());

            initializeCollection();
            log.info("已重新创建集合: {}", milvusConfig.getCollectionName());
        } catch (Exception e) {
            log.error("重新创建集合失败", e);
            throw new VectorStorageException("重新创建集合失败", e);
        }
    }

    /**
     * 创建集合（含 schema、BM25 Function 与索引；2.5 的 createCollection 支持同时建索引）
     */
    private void createCollection() {
        try {
            CreateCollectionReq.CollectionSchema schema = milvusClient.createSchema();

            schema.addField(AddFieldReq.builder()
                    .fieldName(CHUNK_ID_FIELD)
                    .dataType(DataType.Int64)          // 对应 DocumentChunk 的 Long id
                    .isPrimaryKey(true)
                    .autoID(false)
                    .build());

            schema.addField(AddFieldReq.builder()
                    .fieldName(VECTOR_FIELD)
                    .dataType(DataType.FloatVector)
                    .dimension(milvusConfig.getVectorDimension())
                    .build());

            schema.addField(AddFieldReq.builder()
                    .fieldName(DOCUMENT_ID_FIELD)
                    .dataType(DataType.VarChar)
                    .maxLength(64)
                    .build());

            schema.addField(AddFieldReq.builder()
                    .fieldName(CHUNK_INDEX_FIELD)
                    .dataType(DataType.Int32)
                    .build());

            // BM25 输入字段：必须开 analyzer，中文用 chinese（默认 analyzer 对中文子词召回失败）
            schema.addField(AddFieldReq.builder()
                    .fieldName(TEXT_FIELD)
                    .dataType(DataType.VarChar)
                    .maxLength(TEXT_MAX_LENGTH)
                    .enableAnalyzer(true)
                    .analyzerParams(Map.of("type", "chinese"))
                    .build());

            schema.addField(AddFieldReq.builder()
                    .fieldName(SPARSE_FIELD)
                    .dataType(DataType.SparseFloatVector)
                    .build());

            // 业务编号数组：BM25 稀疏路对 "YC-HY-2026-0002" 这类编号的区分性 token 不参与匹配
            // （实测三个不同编号返回同一条同分数），故用标量精确过滤补齐。
            schema.addField(AddFieldReq.builder()
                    .fieldName(CODES_FIELD)
                    .dataType(DataType.Array)
                    .elementType(DataType.VarChar)
                    .maxLength(CODE_MAX_LENGTH)
                    .maxCapacity(CODE_MAX_CAPACITY)
                    .build());

            // BM25 Function：text → sparse（输出字段索引 metric 必须是 BM25）
            schema.addFunction(CreateCollectionReq.Function.builder()
                    .name(BM25_FUNCTION)
                    .functionType(FunctionType.BM25)
                    .inputFieldNames(List.of(TEXT_FIELD))
                    .outputFieldNames(List.of(SPARSE_FIELD))
                    .build());

            milvusClient.createCollection(CreateCollectionReq.builder()
                    .collectionName(milvusConfig.getCollectionName())
                    .description("知识库文档块向量存储（稠密+BM25稀疏）")
                    .collectionSchema(schema)
                    .indexParams(List.of(
                            IndexParam.builder()
                                    .fieldName(VECTOR_FIELD)
                                    .indexType(IndexParam.IndexType.HNSW)
                                    .metricType(IndexParam.MetricType.IP)
                                    .build(),
                            IndexParam.builder()
                                    .fieldName(SPARSE_FIELD)
                                    .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                                    .metricType(IndexParam.MetricType.BM25)
                                    .build(),
                            IndexParam.builder()
                                    .fieldName(CODES_FIELD)
                                    .indexType(IndexParam.IndexType.INVERTED)
                                    .build()))
                    .build());

        } catch (Exception e) {
            log.error("创建Milvus集合失败", e);
            throw new VectorStorageException("创建集合失败", e);
        }
    }

    /**
     * 加载集合到内存
     */
    private void loadCollection() {
        try {
            milvusClient.loadCollection(LoadCollectionReq.builder()
                    .collectionName(milvusConfig.getCollectionName())
                    .build());
        } catch (Exception e) {
            log.error("加载Milvus集合失败", e);
            throw new VectorStorageException("加载集合失败", e);
        }
    }
}
