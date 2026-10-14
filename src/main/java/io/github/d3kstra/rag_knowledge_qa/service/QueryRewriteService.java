package io.github.d3kstra.rag_knowledge_qa.service;

import io.github.d3kstra.rag_knowledge_qa.service.ChatSessionStore.Turn;

import java.util.List;

/**
 * D6 多轮检索改写：把依赖上下文的最新问题改写成可独立检索的 standalone 问题。
 * 这是"缓存 key 层不做 LLM 归一化"决策的另一半——需要上下文的改写只在记忆层、
 * 带着完整对话历史做一次，结果同时供缓存与检索使用。
 */
public interface QueryRewriteService {

    /**
     * @param history 最近的对话轮次（不含本轮）
     * @return 改写后的独立问题；改写失败时必须降级返回原问题，绝不抛异常
     */
    String rewrite(String question, List<Turn> history);
}
