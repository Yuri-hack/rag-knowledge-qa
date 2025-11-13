package io.github.yuri_hack.rag_knowledge_qa.vector.impl;

import io.github.yuri_hack.rag_knowledge_qa.config.MilvusConfig;
import io.github.yuri_hack.rag_knowledge_qa.dto.internal.VectorSearchResult;
import io.github.yuri_hack.rag_knowledge_qa.entity.DocumentChunk;
import io.github.yuri_hack.rag_knowledge_qa.exception.VectorStorageException;
import io.github.yuri_hack.rag_knowledge_qa.embed.EmbeddingService;
import io.github.yuri_hack.rag_knowledge_qa.vector.VectorStoreService;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResultData;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
public class MilvusVectorServiceImpl implements VectorStoreService {

    private final MilvusServiceClient milvusClient;
    private final EmbeddingService embeddingService;
    private final MilvusConfig milvusConfig;

    private static final String VECTOR_FIELD = "vector";
    private static final String DOCUMENT_ID_FIELD = "document_id";
    private static final String CHUNK_INDEX_FIELD = "chunk_index";
    private static final String CHUNK_ID_FIELD = "chunk_id";

    public MilvusVectorServiceImpl(MilvusServiceClient milvusClient, EmbeddingService embeddingService, MilvusConfig milvusConfig) {
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
        this.milvusConfig = milvusConfig;
    }

    @Override
    public List<Long> insertVectors(List<DocumentChunk> chunks) throws VectorStorageException {
        if (chunks == null || chunks.isEmpty()) {
            log.warn("插入向量数据为空");
            return Collections.emptyList();
        }

        try {
            // 准备插入数据
            List<List<Float>> vectors = new ArrayList<>();
            List<String> documentIds = new ArrayList<>();
            List<Integer> chunkIndexes = new ArrayList<>();
            List<Long> chunkIds = new ArrayList<>();

            for (DocumentChunk chunk : chunks) {
                float[] embedding = embeddingService.getEmbedding(chunk.getContent());
                if (embedding == null) {
                    log.warn("文档块 {} 的向量为空，跳过插入", chunk.getId());
                    continue;
                }

                // 转换向量格式
                List<Float> vector = IntStream.range(0, embedding.length)
                        .mapToObj(i -> embedding[i])
                        .collect(Collectors.toList());

                vectors.add(vector);
                documentIds.add(chunk.getDocumentId());
                chunkIndexes.add(chunk.getChunkIndex());
                chunkIds.add(chunk.getId());
            }

            if (vectors.isEmpty()) {
                log.warn("没有有效的向量数据可插入");
                return Collections.emptyList();
            }

            // 构建插入参数
            List<InsertParam.Field> fields = Arrays.asList(
                    new InsertParam.Field(VECTOR_FIELD, vectors),
                    new InsertParam.Field(DOCUMENT_ID_FIELD, documentIds),
                    new InsertParam.Field(CHUNK_INDEX_FIELD, chunkIndexes),
                    new InsertParam.Field(CHUNK_ID_FIELD, chunkIds)
            );

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(milvusConfig.getCollectionName())
                    .withFields(fields)
                    .build();

            R<MutationResult> response = milvusClient.insert(insertParam);

            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new VectorStorageException("插入向量失败: " + response.getMessage());
            }

            List<Long> milvusIds = response.getData().getIDs().getIntId().getDataList();
            log.info("成功插入 {} 个向量到Milvus，生成的ID数量: {}", chunks.size(), milvusIds.size());
            return milvusIds;

        } catch (Exception e) {
            log.error("插入向量到Milvus失败", e);
            throw new VectorStorageException("向量插入失败", e);
        }
    }

    @Override
    public List<VectorSearchResult> searchSimilarVectors(float[] queryVector, int topK) throws VectorStorageException {
        try {
            // 转换查询向量格式
            List<Float> queryVectorList = new ArrayList<>();
            for (float value : queryVector) {
                queryVectorList.add(value);
            }
            List<List<Float>> searchVectors = Collections.singletonList(queryVectorList);

            // 构建搜索参数
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(milvusConfig.getCollectionName())
                    .withMetricType(MetricType.COSINE)
                    .withOutFields(Arrays.asList(DOCUMENT_ID_FIELD, CHUNK_INDEX_FIELD, CHUNK_ID_FIELD))
                    .withTopK(topK)
                    .withVectors(searchVectors)
                    .withVectorFieldName(VECTOR_FIELD)
                    .withParams("{\"nprobe\":10}")
                    .build();

            R<SearchResults> response = milvusClient.search(searchParam);

            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new VectorStorageException("向量搜索失败: " + response.getMessage());
            }

            // 处理搜索结果
            List<VectorSearchResult> vectorSearchResults = new ArrayList<>();

            // 创建 SearchResultsWrapper 来解析结果
            SearchResultData searchResultData = response.getData().getResults();
            SearchResultsWrapper resultsWrapper = new SearchResultsWrapper(searchResultData);

            // 遍历查询向量
            for (int i = 0; i < searchVectors.size(); i++) {
                // 获取当前查询向量的搜索结果
                List<SearchResultsWrapper.IDScore> idScores = resultsWrapper.getIDScore(i);

                for (SearchResultsWrapper.IDScore idScore : idScores) {
                    double similarity = idScore.getScore();

                    try {
                        String documentId = idScore.get(DOCUMENT_ID_FIELD).toString();
                        int chunkIndex = ((Number) idScore.get(CHUNK_INDEX_FIELD)).intValue();
                        long chunkId = ((Number) idScore.get(CHUNK_ID_FIELD)).longValue();

                        // 创建搜索结果对象 - 需要确保 VectorSearchResult 有 chunkId 字段和对应的构造方法/setter
                        VectorSearchResult result = VectorSearchResult.of(chunkIndex, similarity, documentId, chunkId);
                        vectorSearchResults.add(result);
                    } catch (Exception fieldException) {
                        log.warn("处理搜索结果字段时出错，跳过该结果。Score: {}", similarity, fieldException);
                    }
                }
            }

            log.debug("向量搜索完成，查询向量维度: {}, 返回结果数: {}", queryVector.length, vectorSearchResults.size());

            return vectorSearchResults;

        } catch (Exception e) {
            log.error("Milvus向量搜索失败，查询向量维度: {}, topK: {}", queryVector.length, topK, e);
            throw new VectorStorageException("向量搜索失败: " + e.getMessage(), e);
        }
    }

    // 设置 SearchResult 对象的字段
    private void setSearchResultFields(VectorSearchResult result, Integer chunkIndex,
                                       double similarity, String documentId, Long chunkId) {
        try {
            result.setChunkIndex(chunkIndex);
            result.setSimilarity(similarity);
            result.setDocumentId(documentId);
            result.setChunkId(chunkId);
        } catch (Exception e) {
            log.warn("设置SearchResult字段失败", e);
        }
    }
}