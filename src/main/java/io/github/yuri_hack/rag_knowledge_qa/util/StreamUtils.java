package io.github.yuri_hack.rag_knowledge_qa.util;

import io.github.yuri_hack.rag_knowledge_qa.dto.response.StreamChatResponse;
import io.github.yuri_hack.rag_knowledge_qa.dto.response.UsageInfo;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

public class StreamUtils {

    // 流式分块大小（字符数）
    private static final int STREAM_CHUNK_SIZE = 50;

    /**
     * 字符串转为流式输出
     * 避免命中缓存 直接返回 用户感受割裂
     */
    public static Flux<StreamChatResponse> str2StreamChatResponse(String str) {
        List<String> chunks = splitIntoChunks(str, STREAM_CHUNK_SIZE);

        return Flux.fromIterable(chunks)
                .index()
                .map(tuple -> {
                    long index = tuple.getT1();
                    String chunk = tuple.getT2();
                    boolean isLast = index == chunks.size() - 1;

                    return StreamChatResponse.builder()
                            .content(chunk)
                            .finished(isLast)
                            .errorMessage("")
                            .usage(new UsageInfo())
                            .build();
                });
    }

    /**
     * 简单文本分块
     */
    private static List<String> splitIntoChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunkSize) {
            int end = Math.min(text.length(), i + chunkSize);
            chunks.add(text.substring(i, end));
        }
        return chunks;
    }
}
