package io.github.yuri_hack.rag_knowledge_qa.splitter.impl;

import io.github.yuri_hack.rag_knowledge_qa.splitter.TextSplitter;
import io.github.yuri_hack.rag_knowledge_qa.splitter.model.TextChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Component
public class SlidingWindowTextSplitter implements TextSplitter {

    private static final Pattern SENTENCE_PATTERN = Pattern.compile(
        "[^.!?\\s][^.!?]*(?:[.!?](?!['\"]?\\s|$)[^.!?]*)*[.!?]?['\"]?(?=\\s|$)",
        Pattern.MULTILINE
    );

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

    /**
     * 提取句子
     */
    private List<String> extractSentences(String content) {
        List<String> sentences = new ArrayList<>();

        // 首先按段落分割
        String[] paragraphs = PARAGRAPH_PATTERN.split(content);

        for (String paragraph : paragraphs) {
            if (paragraph.trim().isEmpty()) continue;

            // 在段落内分割句子
            java.util.regex.Matcher matcher = SENTENCE_PATTERN.matcher(paragraph);
            while (matcher.find()) {
                String sentence = matcher.group().trim();
                if (sentence.length() > 5) { // 过滤过短的句子
                    sentences.add(sentence);
                }
            }

            // 如果正则匹配失败，使用简单分割作为备选
            if (sentences.isEmpty()) {
                String[] fallbackSentences = paragraph.split("[.!?]+");
                for (String sentence : fallbackSentences) {
                    String trimmed = sentence.trim();
                    if (trimmed.length() > 5) {
                        sentences.add(trimmed);
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
        // 移除多余的空白字符
        String processed = content.replaceAll("\\s+", " ");

        // 标准化换行符
        processed = processed.replaceAll("\\r\\n|\\r", "\n");

        // 移除首尾空白
        processed = processed.trim();

        return processed;
    }
}