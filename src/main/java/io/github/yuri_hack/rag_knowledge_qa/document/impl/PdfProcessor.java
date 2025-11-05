package io.github.yuri_hack.rag_knowledge_qa.document.impl;

import io.github.yuri_hack.rag_knowledge_qa.dto.internal.ProcessResult;
import io.github.yuri_hack.rag_knowledge_qa.exception.FileProcessingException;
import io.github.yuri_hack.rag_knowledge_qa.document.FileProcessor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * pdf文档处理器
 */
@Slf4j
@Component
public class PdfProcessor implements FileProcessor {
    
    @Override
    public ProcessResult process(MultipartFile file) throws FileProcessingException {
        try (PDDocument document = PDDocument.load(file.getInputStream())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String content = stripper.getText(document);
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("pageCount", document.getNumberOfPages());
            metadata.put("charCount", content.length());
            
            return new ProcessResult(content, metadata);
        } catch (IOException e) {
            log.error("PDF文件处理失败: {}", file.getOriginalFilename(), e);
            throw new FileProcessingException("PDF文件处理失败", e);
        }
    }
    
    @Override
    public Set<String> getSupportedFileTypes() {
        return Set.of("pdf");
    }
    
    @Override
    public String getProcessorName() {
        return "PdfProcessor";
    }
}