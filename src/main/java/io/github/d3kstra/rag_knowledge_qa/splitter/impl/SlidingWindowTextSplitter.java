package io.github.d3kstra.rag_knowledge_qa.splitter.impl;

import io.github.d3kstra.rag_knowledge_qa.splitter.TextSplitter;
import io.github.d3kstra.rag_knowledge_qa.splitter.model.TextChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Component
public class SlidingWindowTextSplitter implements TextSplitter {

    /**
     * 断句规则（无损优先）：
     * - 中文句读（。！？；）与换行：直接断句；
     * - ASCII .!?：仅在后接空白时断句——否则会把 1.5 / 1:1.5 这类数字切成两半，
     *   被"过短句过滤"丢掉后索引里就永久缺了数字（2026-10-13 实测：1:1.5 → 1:1.）。
     */
    private static final Pattern CJK_SENTENCE_END = Pattern.compile("(?<=[。！？；])");

    private static final Pattern ASCII_SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    /** 过短片段（表格分隔线残留等）不单独成句 */
    private static final int MIN_SENTENCE_LENGTH = 5;

    private static final Pattern PARAGRAPH_PATTERN = Pattern.compile("\\n\\s*\\n");

    @Value("${rag.chunk.size:1000}")
    private int chunkSize;

    @Value("${rag.chunk.overlap:200}")
    private int chunkOverlap;

    /**
     * 使用滑动窗口分割文本
     */
    public List<TextChunk> splitText(String content, String fileName, String documentId) {
        if (content == null || content.trim().isEmpty()) {
            log.warn("空内容文件: {}", fileName);
            return new ArrayList<>();
        }

        // 预处理文本
        String processedContent = preprocessText(content);

        return splitBySentences(processedContent, fileName, documentId);
    }

    /**
     * 基于句子的滑动窗口分割
     */
    private List<TextChunk> splitBySentences(String content, String fileName, String documentId) {
        List<String> sentences = extractSentences(content);
        log.debug("从文件 {} 中提取了 {} 个句子", fileName, sentences.size());

        List<TextChunk> chunks = new ArrayList<>();
        int startIndex = 0;
        int chunkIndex = 0;

        while (startIndex < sentences.size()) {
            TextChunk chunk = buildChunkWithSlidingWindow(sentences, startIndex, fileName, documentId, chunkIndex);
            chunks.add(chunk);

            // 计算下一个窗口的起始位置
            int sentencesInChunk = chunk.getSentencesCount();
            if (sentencesInChunk <= 0) break;

            // 滑动窗口：前进 (chunkSize - overlap) 个句子
            int stepSize = Math.max(1, sentencesInChunk - calculateOverlapSentences(sentences, startIndex));
            startIndex += stepSize;
            chunkIndex++;
        }

        log.info("文件 {} 使用句子滑动窗口分割为 {} 个块", fileName, chunks.size());
        return chunks;
    }

    /**
     * 构建滑动窗口块
     */
    private TextChunk buildChunkWithSlidingWindow(List<String> sentences, int startIndex,
                                                 String fileName, String documentId, int chunkIndex) {
        StringBuilder chunkContent = new StringBuilder();
        int currentLength = 0;
        int sentenceCount = 0;

        // 添加句子直到达到块大小
        for (int i = startIndex; i < sentences.size(); i++) {
            String sentence = sentences.get(i).trim();
            if (sentence.isEmpty()) continue;

            int sentenceLength = sentence.length();

            // 如果添加这个句子会超过块大小，且已经有内容，则停止
            if (currentLength + sentenceLength > chunkSize && currentLength > 0) {
                break;
            }

            if (!chunkContent.isEmpty()) {
                chunkContent.append(" ");
                currentLength += 1;
            }

            chunkContent.append(sentence);
            currentLength += sentenceLength;
            sentenceCount++;

            // 如果达到块大小，停止添加更多句子
            if (currentLength >= chunkSize) {
                break;
            }
        }

        TextChunk chunk = new TextChunk();
        chunk.setContent(chunkContent.toString());
        chunk.setFileName(fileName);
        chunk.setDocumentId(documentId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setSentencesCount(sentenceCount);

        return chunk;
    }

    /**
     * 计算重叠的句子数量
     */
    private int calculateOverlapSentences(List<String> sentences, int startIndex) {
        int overlapChars = 0;
        int overlapSentences = 0;

        // 从当前起始位置向前计算重叠的句子
        for (int i = startIndex; i >= 0 && overlapChars < chunkOverlap; i--) {
            if (i >= sentences.size()) continue;

            String sentence = sentences.get(i);
            overlapChars += sentence.length() + 1; // +1 for space
            overlapSentences++;

            if (overlapChars >= chunkOverlap) {
                break;
            }
        }

        return Math.max(1, overlapSentences);
    }

    /** 提取句子：段落 → 行 → 中文句读 → ASCII 句读（仅后接空白时），逐级细分且不丢字符。 */
    private List<String> extractSentences(String content) {
        List<String> sentences = new ArrayList<>();
        for (String paragraph : PARAGRAPH_PATTERN.split(content)) {
            for (String line : paragraph.split("\\n")) {
                for (String cjkPiece : CJK_SENTENCE_END.split(line)) {
                    for (String piece : ASCII_SENTENCE_END.split(cjkPiece)) {
                        String sentence = piece.trim();
                        if (sentence.length() > MIN_SENTENCE_LENGTH) {
                            sentences.add(sentence);
                        }
                    }
                }
            }
        }
        return sentences;
    }

    /**
     * 文本预处理
     */
    private String preprocessText(String content) {
        // 保留换行：表格/列表行的自然边界
        String processed = content.replaceAll("[ \\t\\x0B\\f]+", " ");
        processed = processed.replaceAll("\\r\\n|\\r", "\n");
        processed = processed.trim();
        return processed;
    }
}