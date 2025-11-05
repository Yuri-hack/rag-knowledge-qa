package io.github.yuri_hack.rag_knowledge_qa.document;

import io.github.yuri_hack.rag_knowledge_qa.dto.internal.ProcessResult;
import io.github.yuri_hack.rag_knowledge_qa.exception.FileProcessingException;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

public interface FileProcessor {
    /**
     * 处理文件并返回文本内容
     */
    ProcessResult process(MultipartFile file) throws FileProcessingException;

    /**
     * 支持的文件类型
     */
    Set<String> getSupportedFileTypes();

    /**
     * 处理器名称
     */
    String getProcessorName();
}