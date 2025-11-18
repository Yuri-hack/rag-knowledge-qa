package io.github.yuri_hack.rag_knowledge_qa.knowledge.impl;

import io.github.yuri_hack.rag_knowledge_qa.document.FileProcessor;
import io.github.yuri_hack.rag_knowledge_qa.document.FileProcessorFactory;
import io.github.yuri_hack.rag_knowledge_qa.dto.internal.KnowledgeSearchResult;
import io.github.yuri_hack.rag_knowledge_qa.dto.internal.ProcessResult;
import io.github.yuri_hack.rag_knowledge_qa.dto.internal.RerankResult;
import io.github.yuri_hack.rag_knowledge_qa.dto.internal.VectorSearchResult;
import io.github.yuri_hack.rag_knowledge_qa.dto.request.FileUploadRequest;
import io.github.yuri_hack.rag_knowledge_qa.dto.request.SearchRequest;
import io.github.yuri_hack.rag_knowledge_qa.embed.EmbeddingService;
import io.github.yuri_hack.rag_knowledge_qa.entity.DocumentChunk;
import io.github.yuri_hack.rag_knowledge_qa.entity.UploadedDocument;
import io.github.yuri_hack.rag_knowledge_qa.enums.DocumentStatus;
import io.github.yuri_hack.rag_knowledge_qa.knowledge.KnowledgeBaseService;
import io.github.yuri_hack.rag_knowledge_qa.repository.DocumentChunkRepository;
import io.github.yuri_hack.rag_knowledge_qa.repository.UploadedDocumentRepository;
import io.github.yuri_hack.rag_knowledge_qa.rerank.RerankerService;
import io.github.yuri_hack.rag_knowledge_qa.splitter.TextSplitter;
import io.github.yuri_hack.rag_knowledge_qa.splitter.model.TextChunk;
import io.github.yuri_hack.rag_knowledge_qa.vector.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final FileProcessorFactory fileProcessorFactory;
    private final TextSplitter textSplitter;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final DocumentChunkRepository chunkRepository;
    private final UploadedDocumentRepository documentRepository;
    private final RerankerService rerankerService;

    public String uploadAndProcessFile(FileUploadRequest request) {
        // 自增id作主键 documentId索引业务id
        String documentId = generateDocumentId();

        try {
            // 保存文档记录
            UploadedDocument document = createUploadedDocument(request, documentId);
            documentRepository.save(document);

            // 处理文件
            ProcessResult processResult = processFile(request.getFile());

            // 分割文本
            List<TextChunk> textChunks = textSplitter.splitText(
                    processResult.getContent(),
                    request.getFileName(),
                    documentId
            );

            // 生成向量并保存
            List<DocumentChunk> documentChunks = createDocumentChunks(textChunks);
            chunkRepository.saveAll(documentChunks);

            // 插入到Milvus
            vectorStoreService.insertVectors(documentChunks);

            // 更新文档状态
            document.setStatus(DocumentStatus.COMPLETED);
            document.setUpdateTime(LocalDateTime.now());
            documentRepository.save(document);

            log.info("文件处理完成: {}, 生成 {} 个块", request.getFileName(), documentChunks.size());
            return documentId;

        } catch (Exception e) {
            log.error("文件处理失败: {}", request.getFileName(), e);
            updateDocumentStatus(documentId, DocumentStatus.FAILED);
            throw new RuntimeException("文件处理失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<KnowledgeSearchResult> searchKnowledge(SearchRequest request) {
        try {
            // 生成查询向量
            float[] queryVector = embeddingService.getEmbedding(request.getQuery());

            // 在Milvus中搜索相似向量
            List<VectorSearchResult> vectorResults = vectorStoreService.searchSimilarVectors(queryVector, request.getTopK());

            // 获取完整的文档块信息
            List<KnowledgeSearchResult> knowledgeResults = enrichSearchResults(vectorResults);

            // 应用重排序
            List<KnowledgeSearchResult> rerankedSearchResults = applyReranking(request.getQuery(), knowledgeResults);

            // 动态截断结果
            return dynamicCutOff(rerankedSearchResults, request.getTopRatio());
        } catch (Exception e) {
            log.error("知识检索失败: {}", request.getQuery(), e);
            throw new RuntimeException("知识检索失败: " + e.getMessage(), e);
        }
    }

    @Override
    public double getMaxSimilarity(String query) {
        try {
            // 生成查询向量
            float[] queryVector = embeddingService.getEmbedding(query);

            // 在Milvus中搜索相似向量，只取最相似的1个结果
            List<VectorSearchResult> vectorResults = vectorStoreService.searchSimilarVectors(queryVector, 1);

            if (vectorResults.isEmpty()) {
                log.warn("未找到相似的知识片段，查询: {}", query);
                return 0.0;
            }

            // 返回最高相似度
            double maxSimilarity = vectorResults.get(0).getSimilarity();
            log.debug("查询 '{}' 的最高相似度: {}", query, maxSimilarity);
            return maxSimilarity;

        } catch (Exception e) {
            log.error("获取最高相似度失败: {}", query, e);
            return 0.0;
        }
    }


    /**
     * 按比例截取前topRatio的数据
     */
    private List<KnowledgeSearchResult> dynamicCutOff(List<KnowledgeSearchResult> searchResults, Double topRatio) {
        // 按rerank score降序排序
        searchResults.sort((a, b) -> Double.compare(b.getRerankScore(), a.getRerankScore()));
        int take = computeTakeCount(searchResults.size(), topRatio);
        return searchResults.subList(0, take);
    }

    /**
     * 计算要取的条数：
     * - topRatio <= 0 : 不筛选（返回全部）
     * - 0 < topRatio < 1 : 按比例取（向上取整，至少取 1）
     * - topRatio >= 1 : 将 topRatio 当作绝对数量（取 min(topRatio, size)）
     */
    private int computeTakeCount(int total, double topRatio) {
        if (total <= 0) return 0;
        if (topRatio <= 0.0) return total;
        if (topRatio > 0.0 && topRatio < 1.0) {
            int take = (int) Math.ceil(total * topRatio);
            return Math.max(1, Math.min(total, take));
        } else {
            // topRatio >= 1.0, treat as absolute count
            int take = (int) topRatio;
            return Math.max(1, Math.min(total, take));
        }
    }

    /**
     * 应用重排序到搜索结果
     */
    private List<KnowledgeSearchResult> applyReranking(String query,
                                                       List<KnowledgeSearchResult> knowledgeResults) {
        try {
            List<RerankResult> rerankedResults = rerankerService.rerankKnowledgeResults(query, knowledgeResults);

            // 将重排序结果转换回KnowledgeSearchResult格式
            return convertToKnowledgeSearchResults(rerankedResults);

        } catch (Exception e) {
            log.warn("重排序处理失败，返回原始搜索结果: {}", e.getMessage());
            return knowledgeResults;
        }
    }

    /**
     * 将重排序结果转换为知识搜索结果
     */
    private List<KnowledgeSearchResult> convertToKnowledgeSearchResults(List<RerankResult> rerankedResults) {
        return rerankedResults.stream()
                .map(this::convertToKnowledgeSearchResult)
                .collect(Collectors.toList());
    }

    /**
     * 单个重排序结果转换
     */
    private KnowledgeSearchResult convertToKnowledgeSearchResult(RerankResult rerankResult) {
        KnowledgeSearchResult result = new KnowledgeSearchResult();
        result.setChunkIndex(rerankResult.getChunkIndex());
        result.setSimilarity(rerankResult.getSimilarity());
        result.setDocumentId(rerankResult.getDocumentId());
        result.setFileName(rerankResult.getFileName());
        result.setContent(rerankResult.getContent());
        result.setRerankScore(rerankResult.getRerankScore());
        result.setChunkId(rerankResult.getDocumentChunkId());
        return result;
    }

    private ProcessResult processFile(MultipartFile file) {
        String fileExtension = getFileExtension(Objects.requireNonNull(file.getOriginalFilename()));
        Optional<FileProcessor> processorOptional = fileProcessorFactory.getProcessor(fileExtension);

        if (processorOptional.isEmpty()) {
            throw new IllegalArgumentException("不支持的文件类型: " + fileExtension);
        }

        return processorOptional.get().process(file);
    }

    private List<DocumentChunk> createDocumentChunks(List<TextChunk> textChunks) {
        List<DocumentChunk> documentChunks = new ArrayList<>();

        for (TextChunk textChunk : textChunks) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocumentId(textChunk.getDocumentId());
            chunk.setFileName(textChunk.getFileName());
            chunk.setChunkIndex(textChunk.getChunkIndex());
            chunk.setContent(textChunk.getContent());
            chunk.setCreateTime(LocalDateTime.now());

            documentChunks.add(chunk);
        }

        return documentChunks;
    }

    private String generateDocumentId() {
        return "DOC_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private UploadedDocument createUploadedDocument(FileUploadRequest request, String documentId) {
        UploadedDocument document = new UploadedDocument();
        document.setDocumentId(documentId);
        document.setFileName(request.getFileName());
        document.setFileType(getFileExtension(request.getFileName()));
        document.setFileSize(request.getFile().getSize());
        document.setDescription(request.getDescription());
        document.setStatus(DocumentStatus.PROCESSING);
        document.setCreateTime(LocalDateTime.now());
        document.setUpdateTime(LocalDateTime.now());
        return document;
    }

    private void updateDocumentStatus(String documentId, DocumentStatus status) {
        documentRepository.findByDocumentId(documentId).ifPresent(document -> {
            document.setStatus(status);
            document.setUpdateTime(LocalDateTime.now());
            documentRepository.save(document);
        });
    }

    private String getFileExtension(String fileName) {
        return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
    }

    /**
     * 将向量搜索结果转换为完整知识搜索结果
     */
    private List<KnowledgeSearchResult> enrichSearchResults(
            List<VectorSearchResult> vectorResults) {

        if (vectorResults.isEmpty()) {
            return Collections.emptyList();
        }

        // 从MySQL批量查询完整数据
        List<Long> chunkIds = vectorResults.stream()
                .map(VectorSearchResult::getChunkId)
                .collect(Collectors.toList());

        List<DocumentChunk> documentChunks = chunkRepository.findByIdIn(chunkIds);
        Map<Long, DocumentChunk> chunkMap = documentChunks.stream()
                .collect(Collectors.toMap(DocumentChunk::getId, chunk -> chunk));

        // 组装完整结果
        List<KnowledgeSearchResult> knowledgeResults = new ArrayList<>();

        for (VectorSearchResult vectorResult : vectorResults) {
            DocumentChunk chunk = chunkMap.get(vectorResult.getChunkId());
            if (chunk != null) {
                KnowledgeSearchResult knowledgeResult = KnowledgeSearchResult.from(vectorResult, chunk);
                knowledgeResults.add(knowledgeResult);
            }
        }

        log.info("知识搜索完成, 返回 {} 个结果", knowledgeResults.size());
        return knowledgeResults;
    }
}