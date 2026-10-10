package io.github.yuri_hack.rag_knowledge_qa.controller;

import io.github.yuri_hack.rag_knowledge_qa.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 评测/调试用的缓存总开关：一次性切换三级缓存（精确/语义答案/语义文档）。
 * 评测 runner 在跑全量前关闭缓存并 flush，跑完恢复，避免跨轮污染。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/cache")
@RequiredArgsConstructor
public class CacheAdminController {

    private final CacheConfig cacheConfig;

    @PostMapping("/toggle")
    public Map<String, Object> toggle(@RequestParam boolean enabled) {
        cacheConfig.getExact().setEnabled(enabled);
        cacheConfig.getSemanticAnswer().setEnabled(enabled);
        cacheConfig.getSemantic().setEnabled(enabled);
        log.warn("[admin] 三级缓存已{}", enabled ? "开启" : "关闭");
        return status();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("exact", cacheConfig.getExact().isEnabled());
        s.put("semanticAnswer", cacheConfig.getSemanticAnswer().isEnabled());
        s.put("semanticDocument", cacheConfig.getSemantic().isEnabled());
        return s;
    }
}
