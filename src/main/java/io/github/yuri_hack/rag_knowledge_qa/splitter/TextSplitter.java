package io.github.yuri_hack.rag_knowledge_qa.splitter;

import io.github.yuri_hack.rag_knowledge_qa.splitter.model.TextChunk;

import java.util.List;

/**
 * 文本分割器接口
 */
public interface TextSplitter {

    List<TextChunk> splitText(String content, String fileName, String documentId);
}