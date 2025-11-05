package io.github.yuri_hack.rag_knowledge_qa.repository;

import io.github.yuri_hack.rag_knowledge_qa.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {
    
    @Query("SELECT dc FROM DocumentChunk dc WHERE dc.documentId = :documentId AND dc.chunkIndex = :chunkIndex")
    Optional<DocumentChunk> findByDocumentIdAndChunkIndex(@Param("documentId") String documentId, 
                                                         @Param("chunkIndex") Integer chunkIndex);
    
    void deleteByDocumentId(String documentId);

    List<DocumentChunk> findByIdIn(List<Long> chunkIds);
}