package io.github.d3kstra.rag_knowledge_qa.dto.internal;

import io.github.d3kstra.rag_knowledge_qa.entity.DocumentChunk;
import lombok.Data;

@Data
public class KnowledgeSearchResult {
    private String content;         // 文档内容
    private String fileName;        // 文件名
    private Integer chunkIndex;     // 块索引
    private Double similarity;      // 相似度
    private String documentId;      // 文档ID
    private Double rerankScore;     // rerank分数
    private Long chunkId;           // 块id
    private Double lexicalScore;    // BM25 词法分数（仅词法通道命中时有值）

    public static KnowledgeSearchResult from(VectorSearchResult vectorResult,
                                                        DocumentChunk documentChunk) {
        KnowledgeSearchResult result = new KnowledgeSearchResult();
        result.setContent(documentChunk.getContent());
        result.setFileName(documentChunk.getFileName());
        result.setChunkIndex(documentChunk.getChunkIndex());
        result.setSimilarity(vectorResult.getSimilarity());
        result.setDocumentId(vectorResult.getDocumentId());
        result.setChunkId(vectorResult.getChunkId());
        return result;
    }

    /** 由词法通道命中构造（无稠密相似度） */
    public static KnowledgeSearchResult fromChunk(DocumentChunk documentChunk, Double lexicalScore) {
        KnowledgeSearchResult result = new KnowledgeSearchResult();
        result.setContent(documentChunk.getContent());
        result.setFileName(documentChunk.getFileName());
        result.setChunkIndex(documentChunk.getChunkIndex());
        result.setDocumentId(documentChunk.getDocumentId());
        result.setChunkId(documentChunk.getId());
        result.setLexicalScore(lexicalScore);
        return result;
    }
}