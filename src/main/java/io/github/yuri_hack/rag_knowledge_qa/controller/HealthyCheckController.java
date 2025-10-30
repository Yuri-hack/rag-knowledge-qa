package io.github.yuri_hack.rag_knowledge_qa.controller;

import io.github.yuri_hack.rag_knowledge_qa.service.HealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/healthy")
public class HealthyCheckController {

    private final HealthService healthService;

    @GetMapping("/check")
    public Boolean check() {
        return healthService.isRedisConnected() && healthService.isMilvusConnected();
    }
}