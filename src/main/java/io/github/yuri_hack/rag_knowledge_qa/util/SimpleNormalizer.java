package io.github.yuri_hack.rag_knowledge_qa.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.regex.Pattern;

@Slf4j
@Component
public class SimpleNormalizer {

    private static final Pattern PATTERN_PUNCTUATION = Pattern.compile("[\\p{P}\\p{S}]");
    private static final Pattern PATTERN_EXTRA_SPACES = Pattern.compile("\\s+");

    /**
     * 查询归一化处理
     */
    public String normalize(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "";
        }

        try {
            // 1. 转换为小写
            String normalized = query.toLowerCase();

            // 2. 移除标点符号和特殊字符
            normalized = PATTERN_PUNCTUATION.matcher(normalized).replaceAll(" ");

            // 3. 标准化Unicode字符
            normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC);

            // 5. 去除多余空格并trim
            normalized = PATTERN_EXTRA_SPACES.matcher(normalized).replaceAll(" ").trim();

            log.debug("Query normalized: '{}' -> '{}'", query, normalized);
            return normalized;

        } catch (Exception e) {
            log.warn("Query normalization failed, using original query: {}", query, e);
            return query.toLowerCase().trim();
        }
    }
}