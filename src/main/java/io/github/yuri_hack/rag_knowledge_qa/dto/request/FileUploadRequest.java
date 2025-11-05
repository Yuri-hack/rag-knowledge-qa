package io.github.yuri_hack.rag_knowledge_qa.dto.request;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class FileUploadRequest {

    private String fileName;
    
    private String description;
    
    private MultipartFile file;
}