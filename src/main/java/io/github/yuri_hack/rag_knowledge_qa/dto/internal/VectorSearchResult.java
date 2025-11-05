package io.github.yuri_hack.rag_knowledge_qa.dto.internal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VectorSearchResult {
    private Long chunkId;
    private Integer chunkIndex;
    private Double similarity;
    private String documentId;

    public static VectorSearchResult of(Integer chunkIndex, Double similarity, String documentId, Long chunkId) {
        VectorSearchResult result = new VectorSearchResult();
        result.setChunkIndex(chunkIndex);
        result.setSimilarity(similarity);
        result.setDocumentId(documentId);
        result.setChunkId(chunkId);
        return result;
    }
}
