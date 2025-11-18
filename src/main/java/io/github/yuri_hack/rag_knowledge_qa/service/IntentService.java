package io.github.yuri_hack.rag_knowledge_qa.service;

public interface IntentService {

    /**
     * 企业相关知识问答的分
     * @param question 用户问题
     * @return [0-1] 分数越大和企业知识相关越高
     */
    Double getKnowledgeIntentScore(String question);
}
