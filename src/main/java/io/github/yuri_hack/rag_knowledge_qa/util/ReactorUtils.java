package io.github.yuri_hack.rag_knowledge_qa.util;

import io.github.yuri_hack.rag_knowledge_qa.dto.response.StreamChatResponse;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;

@Slf4j
public final class ReactorUtils {

    private ReactorUtils() {
    }

    /**
     * 把"完整答案的消费者"与 SSE 订阅者解耦（修复原 doOnComplete 方案"客户端中途断连就丢"）：
     * cache() 使上游订阅独立于下游——客户端 cancel 后源照常跑完，由侧车道订阅者
     * 在生成完成时回调；onAnswer 收到完整累积答案（行为与原 doOnComplete 一致）。
     */
    public static Flux<StreamChatResponse> writeAnswerDetached(Flux<StreamChatResponse> source,
                                                               Consumer<String> onAnswer) {
        return Flux.defer(() -> {
            StringBuilder fullAnswer = new StringBuilder();
            Flux<StreamChatResponse> shared = source
                    .doOnNext(r -> {
                        if (r.getContent() != null) {
                            fullAnswer.append(r.getContent());
                        }
                    })
                    .cache();
            shared.subscribe(r -> { },
                    e -> log.warn("侧车道生成失败，跳过回调", e),
                    () -> onAnswer.accept(fullAnswer.toString()));
            return shared;
        });
    }
}
