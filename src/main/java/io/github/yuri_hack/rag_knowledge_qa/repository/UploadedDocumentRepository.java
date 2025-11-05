package io.github.yuri_hack.rag_knowledge_qa.repository;

import io.github.yuri_hack.rag_knowledge_qa.entity.UploadedDocument;
import io.github.yuri_hack.rag_knowledge_qa.enums.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UploadedDocumentRepository extends JpaRepository<UploadedDocument, Long> {
    
    Optional<UploadedDocument> findByDocumentId(String documentId);
    
    List<UploadedDocument> findByStatus(DocumentStatus status);
    
    boolean existsByFileName(String fileName);
}