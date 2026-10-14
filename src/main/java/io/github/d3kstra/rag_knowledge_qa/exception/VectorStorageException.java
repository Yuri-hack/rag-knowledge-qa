package io.github.d3kstra.rag_knowledge_qa.exception;

/**
 * 向量存储异常
 */
public class VectorStorageException extends RuntimeException {

    public VectorStorageException(String message) {
        super(message);
    }
    
    public VectorStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}