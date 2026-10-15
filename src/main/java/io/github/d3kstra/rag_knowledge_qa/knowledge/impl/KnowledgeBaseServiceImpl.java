package io.github.d3kstra.rag_knowledge_qa.knowledge.impl;

import org.springframework.beans.factory.annotation.Value;


import io.github.d3kstra.rag_knowledge_qa.document.FileProcessor;
import io.github.d3kstra.rag_knowledge_qa.document.FileProcessorFactory;
import io.github.d3kstra.rag_knowledge_qa.dto.internal.KnowledgeSearchResult;
import io.github.d3kstra.rag_knowledge_qa.dto.internal.ProcessResult;
import io.github.d3kstra.rag_knowledge_qa.dto.internal.RerankResult;
import io.github.d3kstra.rag_knowledge_qa.dto.internal.VectorSearchResult;
import io.github.d3kstra.rag_knowledge_qa.dto.request.FileUploadRequest;
import io.github.d3kstra.rag_knowledge_qa.dto.request.SearchRequest;
import io.github.d3kstra.rag_knowledge_qa.embed.EmbeddingService;
import io.github.d3kstra.rag_knowledge_qa.entity.DocumentChunk;
import io.github.d3kstra.rag_knowledge_qa.entity.UploadedDocument;
import io.github.d3kstra.rag_knowledge_qa.enums.DocumentStatus;
import io.github.d3kstra.rag_knowledge_qa.knowledge.KnowledgeBaseService;
import io.github.d3kstra.rag_knowledge_qa.repository.DocumentChunkRepository;
import io.github.d3kstra.rag_knowledge_qa.repository.UploadedDocumentRepository;
import io.github.d3kstra.rag_knowledge_qa.rerank.RerankerService;
import io.github.d3kstra.rag_knowledge_qa.splitter.TextSplitter;
import io.github.d3kstra.rag_knowledge_qa.splitter.model.TextChunk;
import io.github.d3kstra.rag_knowledge_qa.vector.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.HashSet;
import java.util.Comparator;
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

    /** 混合检索开关：true=Milvus 服务端原生混合检索（稠密+BM25稀疏+RRF）；false=纯稠密（对照用） */
    @Value("${rag.search.hybrid.enabled:true}")
    private boolean hybridEnabled;

    /** 邻域扩展：0=关闭；1=±1 邻块。检索单元不变，只扩上下文（见 expandNeighbors 注释） */
    @Value("${rag.search.neighbor-window:0}")
    private int neighborWindow;

    /** 邻域扩展后总块数上限 */
    @Value("${rag.search.neighbor-max-total:30}")
    private int neighborMaxTotal;

    /** 只对排名前 N 的命中做邻域扩展（避免噪声扩散） */
    @Value("${rag.search.neighbor-top-hits:5}")
    private int neighborTopHits;

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

            // 召回：hybridEnabled=true 走 Milvus 服务端原生混合检索（dense+sparse+RRF）；
            //       false 走纯稠密（用于"混合 vs 稠密"对照）
            List<VectorSearchResult> vectorResults = hybridEnabled
                    ? vectorStoreService.hybridSearch(queryVector, request.getQuery(), request.getTopK())
                    : vectorStoreService.searchSimilarVectors(queryVector, request.getTopK());

            // 获取完整的文档块信息
            List<KnowledgeSearchResult> knowledgeResults = enrichSearchResults(vectorResults);

            // 应用重排序
            List<KnowledgeSearchResult> rerankedSearchResults = applyReranking(request.getQuery(), knowledgeResults);

            // 动态截断结果
            List<KnowledgeSearchResult> cut = dynamicCutOff(rerankedSearchResults, request.getTopRatio());
            // 邻域扩展：检索单元不变，只在送上下文前补齐相邻块（window<=0 时为无操作）
            return expandNeighbors(new ArrayList<>(cut));
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
     * 邻域扩展（neighbor expansion）。
     *
     * 检索单元保持不变（因此 gold 标注与历史基线仍可比），只在构造上下文前，把排名前
     * {@code neighborTopHits} 个命中块的相邻块（±neighborWindow）一并带上。
     *
     * 动机：本语料 chunk 非常碎（实测 45% 的 chunk < 80 字，最小 12 字），
     * 事实与它的条件/主语可能落在相邻块里；而检索精度已到顶（hit@50 = 38/38 = 100%），
     * 缺的不是召回而是"单块的信息自足性"。邻域扩展直接治这个病，且不破坏任何已有评测资产。
     *
     * {@code neighborWindow <= 0} 时原样返回（与开启前行为完全一致）。
     */
    List<KnowledgeSearchResult> expandNeighbors(List<KnowledgeSearchResult> hits) {
        if (neighborWindow <= 0 || hits == null || hits.isEmpty()) {
            return hits;
        }
        List<KnowledgeSearchResult> expanded = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (KnowledgeSearchResult hit : hits) {
            if (hit.getChunkId() != null) {
                seen.add(hit.getChunkId());
            }
        }
        int rank = 0;
        for (KnowledgeSearchResult hit : hits) {
            expanded.add(hit);
            boolean expandable = rank < neighborTopHits
                    && hit.getFileName() != null && hit.getChunkIndex() != null;
            rank++;
            if (!expandable || expanded.size() >= neighborMaxTotal) {
                continue;
            }
            List<Integer> wanted = new ArrayList<>();
            for (int d = 1; d <= neighborWindow; d++) {
                if (hit.getChunkIndex() - d >= 0) {
                    wanted.add(hit.getChunkIndex() - d);
                }
                wanted.add(hit.getChunkIndex() + d);
            }
            // 复制一份再排序：不假设 repository 返回可变列表（JPA 通常可变，但接口未承诺）
            List<DocumentChunk> neighbors = new ArrayList<>(chunkRepository
                    .findByFileNameAndChunkIndexIn(hit.getFileName(), wanted));
            neighbors.sort(Comparator.comparing(DocumentChunk::getChunkIndex,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            for (DocumentChunk neighbor : neighbors) {
                if (expanded.size() >= neighborMaxTotal) {
                    break;
                }
                if (neighbor.getId() == null || !seen.add(neighbor.getId())) {
                    continue;   // 已是命中块或已补过，不重复
                }
                expanded.add(toNeighbor(neighbor));
            }
        }
        if (expanded.size() > hits.size()) {
            log.info("邻域扩展: 命中 {} 条 → 扩展后 {} 条 (window={}, topHits={}, maxTotal={})",
                    hits.size(), expanded.size(), neighborWindow, neighborTopHits, neighborMaxTotal);
        }
        return expanded;
    }

    /** 相邻块 → 结果对象（标记 neighbor=true，供工具输出标注，避免模型误以为是直接命中） */
    private static KnowledgeSearchResult toNeighbor(DocumentChunk chunk) {
        KnowledgeSearchResult result = new KnowledgeSearchResult();
        result.setChunkId(chunk.getId());
        result.setDocumentId(chunk.getDocumentId());
        result.setFileName(chunk.getFileName());
        result.setChunkIndex(chunk.getChunkIndex());
        result.setContent(chunk.getContent());
        result.setNeighbor(true);
        return result;
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