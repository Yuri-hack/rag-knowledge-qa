package io.github.yuri_hack.rag_knowledge_qa.splitter.model;

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