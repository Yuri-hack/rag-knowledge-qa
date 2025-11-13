package io.github.yuri_hack.rag_knowledge_qa.service;

public interface NormalizeService {
    
    /**
     * 对用户问题进行归一化处理
     * @param question 原始用户问题
     * @return 归一化后的标准问题
     */
    String normalizeQuestion(String question);
}