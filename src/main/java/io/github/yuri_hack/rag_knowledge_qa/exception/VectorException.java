package io.github.yuri_hack.rag_knowledge_qa.exception;

public class VectorException extends RuntimeException {
    public VectorException(String message) {
        super(message);
    }
    
    public VectorException(String message, Throwable cause) {
        super(message, cause);
    }
}
