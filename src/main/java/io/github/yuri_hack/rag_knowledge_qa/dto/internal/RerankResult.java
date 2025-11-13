package io.github.yuri_hack.rag_knowledge_qa.dto.internal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RerankResult {
    private String content;
    private Double rerankScore;
    private Double similarity;
    private Integer chunkIndex;
    private String documentId;
    private String fileName;
    private Long documentChunkId;

    public static RerankResult from(KnowledgeSearchResult knowledgeResult, Double rerankScore) {
        return new RerankResult(
                knowledgeResult.getContent(),
                rerankScore,
                knowledgeResult.getSimilarity(),
                knowledgeResult.getChunkIndex(),
                knowledgeResult.getDocumentId(),
                knowledgeResult.getFileName(),
                knowledgeResult.getChunkId()
        );
    }
}