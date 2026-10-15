package io.github.d3kstra.rag_knowledge_qa.knowledge.impl;

import org.springframework.beans.factory.annotation.Value;

import io.github.d3kstra.rag_knowledge_qa.lexical.LexicalIndexService;

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
    private final LexicalIndexService lexicalIndexService;

    /** 混合检索开关：稠密 + BM25 词法通道 */
    @Value("${rag.search.hybrid.enabled:true}")
    private boolean hybridEnabled;

    /** 词法通道候选数 */
    @Value("${rag.search.hybrid.lexical-top-k:20}")
    private int lexicalTopK;

    /** 送入 rerank 的候选上限（reranker 侧硬上限 50） */
    @Value("${rag.search.hybrid.max-candidates:50}")
    private int maxCandidates;

    /** 精确匹配兜底条数：词法 top-N 若被 rerank 挤出最终结果，强行补回 */
    @Value("${rag.search.hybrid.rescue-count:3}")
    private int lexicalRescueCount;

    /**
     * 混合检索通道实现：
     *  - milvus（默认）：Milvus 2.5 服务端原生混合检索（稠密 + BM25 稀疏 + RRF），不做应用侧融合；
     *  - local：进程内 BM25 词法通道 + 应用侧合并/提权/兜底（保留用于 A/B 对照）。
     */
    @Value("${rag.search.hybrid.provider:milvus}")
    private String hybridProvider;

    /** 邻域扩展：0=关闭；1=±1 邻块。检索单元不变，只扩上下文（见 expandNeighbors 注释） */
    @Value("${rag.search.neighbor-window:0}")
    private int neighborWindow;

    /** 邻域扩展后总块数上限 */
    @Value("${rag.search.neighbor-max-total:30}")
    private int neighborMaxTotal;

    /** 只对排名前 N 的命中做邻域扩展（避免噪声扩散） */
    @Value("${rag.search.neighbor-top-hits:5}")
    private int neighborTopHits;

    private static final String PROVIDER_MILVUS = "milvus";

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

            boolean useMilvusHybrid = hybridEnabled && PROVIDER_MILVUS.equalsIgnoreCase(hybridProvider);

            // 召回：milvus 通道走服务端混合检索（dense+sparse+RRF）；local 通道走纯稠密后应用侧融合
            List<VectorSearchResult> vectorResults = useMilvusHybrid
                    ? vectorStoreService.hybridSearch(queryVector, request.getQuery(), request.getTopK())
                    : vectorStoreService.searchSimilarVectors(queryVector, request.getTopK());

            // 获取完整的文档块信息
            List<KnowledgeSearchResult> knowledgeResults = enrichSearchResults(vectorResults);

            // local 通道专用：进程内 BM25 词法合并（稠密对无意义 token 无法精确匹配，靠词法补齐）
            HybridCandidates hybrid = HybridCandidates.empty();
            List<KnowledgeSearchResult> candidates = knowledgeResults;
            if (hybridEnabled && !useMilvusHybrid) {
                hybrid = mergeCandidates(knowledgeResults,
                        lexicalIndexService.search(request.getQuery(), lexicalTopK));
                candidates = hybrid.merged();
            }

            // 应用重排序
            List<KnowledgeSearchResult> rerankedSearchResults = applyReranking(request.getQuery(), candidates);

            // 动态截断结果
            List<KnowledgeSearchResult> cut = dynamicCutOff(rerankedSearchResults, request.getTopRatio());
            // 精确匹配优先/兜底是 local 通道专用（milvus 通道的精确编号命中由服务端 BM25 路负责）
            List<KnowledgeSearchResult> finalResults = hybridEnabled && !useMilvusHybrid
                    ? rescueExactMatches(cut, rerankedSearchResults, hybrid)
                    : new ArrayList<>(cut);
            // 邻域扩展：检索单元不变，只在送上下文前补齐相邻块（window<=0 时为无操作）
            return expandNeighbors(finalResults);
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
     * 混合检索候选：被 rerank 转换丢弃的字段（词法分数/精确匹配标记）在此留存。
     *
     * @param merged      送入 rerank 的候选（词法命中在前，保证不被 reranker 的 50 条上限截掉）
     * @param lexicalOrder 按 BM25 降序的词法命中 chunkId
     * @param lexicalScore chunkId → BM25 分数
     * @param exactMatch  精确命中稀有 token 的 chunkId（资产编号/单号）
     */
    private record HybridCandidates(List<KnowledgeSearchResult> merged,
                                    List<Long> lexicalOrder,
                                    Map<Long, Double> lexicalScore,
                                    java.util.Set<Long> exactMatch) {
        static HybridCandidates empty() {
            return new HybridCandidates(List.of(), List.of(), Map.of(), java.util.Set.of());
        }
    }

    private HybridCandidates mergeCandidates(List<KnowledgeSearchResult> dense,
                                             List<LexicalIndexService.LexicalHit> lexical) {
        Map<Long, KnowledgeSearchResult> denseByChunk = dense.stream()
                .collect(Collectors.toMap(KnowledgeSearchResult::getChunkId, r -> r, (a, b) -> a));
        List<KnowledgeSearchResult> merged = new ArrayList<>();
        List<Long> lexicalOrder = new ArrayList<>();
        Map<Long, Double> lexicalScore = new java.util.HashMap<>();
        java.util.Set<Long> exactMatch = new java.util.HashSet<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();

        for (LexicalIndexService.LexicalHit hit : lexical) {
            lexicalScore.put(hit.chunkId(), hit.score());
            if (hit.exactMatch()) {
                exactMatch.add(hit.chunkId());
            }
            KnowledgeSearchResult existing = denseByChunk.get(hit.chunkId());
            KnowledgeSearchResult result = existing != null
                    ? existing
                    : KnowledgeSearchResult.fromChunk(toChunk(hit), hit.score());
            result.setLexicalScore(hit.score());
            if (seen.add(hit.chunkId())) {
                merged.add(result);
                lexicalOrder.add(hit.chunkId());
            }
        }
        for (KnowledgeSearchResult r : dense) {
            if (seen.add(r.getChunkId())) {
                merged.add(r);
            }
        }
        if (merged.size() > maxCandidates) {
            log.info("混合检索候选超限，截断 {} → {}", merged.size(), maxCandidates);
            merged = new ArrayList<>(merged.subList(0, maxCandidates));
        }
        return new HybridCandidates(merged, lexicalOrder, lexicalScore, exactMatch);
    }

    /** 词法命中 → DocumentChunk（仅用于构造结果，不落库） */
    private static DocumentChunk toChunk(LexicalIndexService.LexicalHit hit) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(hit.chunkId());
        chunk.setDocumentId(hit.documentId());
        chunk.setFileName(hit.fileName());
        chunk.setChunkIndex(hit.chunkIndex());
        chunk.setContent(hit.content());
        return chunk;
    }

    /**
     * 精确匹配优先 + 被挤出的词法命中兜底。
     *
     * 1) 精确命中（查询含稀有 token，如资产编号）：提到最前——这类查询里 cross-encoder
     *    把"编号相同"视为弱信号（实测会偏好含相似编号片段的干扰行），不能交给它定序；
     * 2) 词法 top-N 若被 rerank 挤出最终结果：替换掉 rerank 分最低的一条补回。
     */
    private List<KnowledgeSearchResult> rescueExactMatches(List<KnowledgeSearchResult> cut,
                                                          List<KnowledgeSearchResult> reranked,
                                                          HybridCandidates hybrid) {
        if (!hybridEnabled || hybrid.lexicalOrder().isEmpty()) {
            return cut;
        }
        // rerank 转换会丢字段，这里按 chunkId 回填词法分数
        for (KnowledgeSearchResult r : cut) {
            Double score = hybrid.lexicalScore().get(r.getChunkId());
            if (score != null) {
                r.setLexicalScore(score);
            }
        }

        List<KnowledgeSearchResult> result = new ArrayList<>(cut);
        if (!hybrid.exactMatch().isEmpty()) {
            List<KnowledgeSearchResult> promoted = new ArrayList<>();
            List<KnowledgeSearchResult> rest = new ArrayList<>();
            for (KnowledgeSearchResult r : result) {
                (hybrid.exactMatch().contains(r.getChunkId()) ? promoted : rest).add(r);
            }
            if (!promoted.isEmpty()) {
                promoted.sort(Comparator.comparing(
                        (KnowledgeSearchResult r) -> hybrid.lexicalScore().getOrDefault(r.getChunkId(), 0.0)).reversed());
                if (promoted.size() != result.size()) {
                    log.info("精确匹配优先: {} 条稀有 token 命中被提到最前", promoted.size());
                }
                result = new ArrayList<>(promoted);
                result.addAll(rest);
            }
        }

        if (lexicalRescueCount <= 0) {
            return result;
        }
        java.util.Set<Long> present = result.stream()
                .map(KnowledgeSearchResult::getChunkId).collect(Collectors.toSet());
        Map<Long, KnowledgeSearchResult> byChunk = reranked.stream()
                .collect(Collectors.toMap(KnowledgeSearchResult::getChunkId, r -> r, (a, b) -> a));

        // 兜底命中集中放在末尾：逐条 set(size-1) 会让后一条把前一条挤掉（只有最后一次生效）
        List<KnowledgeSearchResult> rescuedHits = new ArrayList<>();
        for (Long chunkId : hybrid.lexicalOrder()) {
            if (rescuedHits.size() >= lexicalRescueCount) {
                break;
            }
            if (present.contains(chunkId)) {
                continue;
            }
            KnowledgeSearchResult hit = byChunk.get(chunkId);
            if (hit == null) {
                continue;
            }
            hit.setLexicalScore(hybrid.lexicalScore().get(chunkId));
            rescuedHits.add(hit);
            present.add(chunkId);
        }
        int originalSize = result.size();
        if (!rescuedHits.isEmpty() && originalSize > 0) {
            for (int i = 0; i < rescuedHits.size() && i < originalSize; i++) {
                KnowledgeSearchResult evicted = result.set(originalSize - 1 - i, rescuedHits.get(i));
                log.info("词法命中兜底: 补回 chunkId={}，替换 chunkId={}",
                        rescuedHits.get(i).getChunkId(), evicted.getChunkId());
            }
        } else {
            result.addAll(rescuedHits);
        }
        return result;
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