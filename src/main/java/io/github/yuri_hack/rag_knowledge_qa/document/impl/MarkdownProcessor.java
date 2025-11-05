package io.github.yuri_hack.rag_knowledge_qa.document.impl;

import io.github.yuri_hack.rag_knowledge_qa.dto.internal.ProcessResult;
import io.github.yuri_hack.rag_knowledge_qa.exception.FileProcessingException;
import io.github.yuri_hack.rag_knowledge_qa.document.FileProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * markdown文档处理器
 */
@Slf4j
@Component
public class MarkdownProcessor implements FileProcessor {
    
    @Override
    public ProcessResult process(MultipartFile file) throws FileProcessingException {
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("charCount", content.length());
            metadata.put("lineCount", content.split("\r\n|\r|\n").length);
            
            return new ProcessResult(content, metadata);
        } catch (IOException e) {
            log.error("Markdown文件处理失败: {}", file.getOriginalFilename(), e);
            throw new FileProcessingException("Markdown文件处理失败", e);
        }
    }
    
    @Override
    public Set<String> getSupportedFileTypes() {
        return Set.of("md", "markdown");
    }
    
    @Override
    public String getProcessorName() {
        return "MarkdownProcessor";
    }
}