package io.github.yuri_hack.rag_knowledge_qa.util;

import io.github.yuri_hack.rag_knowledge_qa.dto.response.StreamChatResponse;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;

@Slf4j
public final class ReactorUtils {

    private ReactorUtils() {
    }

    /** 答案消费者与 SSE 订阅者解耦：cache() 使客户端断连后回调仍触发。 */
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
