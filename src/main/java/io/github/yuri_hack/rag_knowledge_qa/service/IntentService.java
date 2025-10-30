package io.github.yuri_hack.rag_knowledge_qa.service;

import io.github.yuri_hack.rag_knowledge_qa.enums.IntentEnum;

public interface IntentService {

    IntentEnum classifyIntent(String question);
}
