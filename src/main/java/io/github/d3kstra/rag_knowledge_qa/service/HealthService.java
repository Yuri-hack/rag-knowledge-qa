package io.github.d3kstra.rag_knowledge_qa.service;

public interface HealthService {

    Boolean isMilvusConnected();

    Boolean isRedisConnected();
}
