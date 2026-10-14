package io.github.d3kstra.rag_knowledge_qa.splitter.model;

import lombok.Data;

@Data
public class TextChunk {
    private String content;
    private String fileName;
    private String documentId;
    private Integer chunkIndex;
    private Integer sentencesCount;
    private Integer endSentenceIndex;
}