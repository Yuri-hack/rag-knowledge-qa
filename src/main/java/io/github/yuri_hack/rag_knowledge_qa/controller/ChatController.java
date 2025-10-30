package io.github.yuri_hack.rag_knowledge_qa.controller;

import io.github.yuri_hack.rag_knowledge_qa.dto.StreamChatResponse;
import io.github.yuri_hack.rag_knowledge_qa.service.ChatOrchestrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatOrchestrationService chatOrchestrationService;

    @GetMapping(value = "/rag/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<StreamChatResponse> ragChatStream(
            @RequestParam String question,
            @RequestParam(defaultValue = "3") int topK) {
        return chatOrchestrationService.chatStream(question, topK)
                .onErrorResume(e -> {
                    log.error("RAG流式调用异常", e);
                    return Flux.just(StreamChatResponse.builder()
                            .content("")
                            .finished(true)
                            .errorMessage("RAG流式调用异常: " + e.getMessage())
                            .build());
                });
    }
}