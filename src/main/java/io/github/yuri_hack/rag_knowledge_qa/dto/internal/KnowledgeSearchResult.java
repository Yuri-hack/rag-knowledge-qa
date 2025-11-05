package io.github.yuri_hack.rag_knowledge_qa.dto.internal;

import io.github.yuri_hack.rag_knowledge_qa.entity.DocumentChunk;
import lombok.Data;

@Data
public class KnowledgeSearchResult {
    private String content;         // 文档内容
    private String fileName;        // 文件名
    private Integer chunkIndex;     // 块索引
    private Double similarity;      // 相似度
    private String documentId;      // 文档ID
    private Double rerankScore;     // rerank分数

    public static KnowledgeSearchResult from(VectorSearchResult vectorResult,
                                                        DocumentChunk documentChunk) {
        KnowledgeSearchResult result = new KnowledgeSearchResult();
        result.setContent(documentChunk.getContent());
        result.setFileName(documentChunk.getFileName());
        result.setChunkIndex(documentChunk.getChunkIndex());
        result.setSimilarity(vectorResult.getSimilarity());
        result.setDocumentId(vectorResult.getDocumentId());
        return result;
    }
}