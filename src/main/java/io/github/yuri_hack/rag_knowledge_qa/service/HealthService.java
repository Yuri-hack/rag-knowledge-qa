package io.github.yuri_hack.rag_knowledge_qa.service;

public interface HealthService {

    Boolean isMilvusConnected();

    Boolean isRedisConnected();
}
