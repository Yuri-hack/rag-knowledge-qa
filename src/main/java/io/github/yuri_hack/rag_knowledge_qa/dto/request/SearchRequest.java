package io.github.yuri_hack.rag_knowledge_qa.dto.request;

import lombok.Data;

@Data
public class SearchRequest {
    private String query;

    /** 召回池大小 N */
    private Integer topK = 10;

    /**
     * 截断口径（语义随区间变化，见 KnowledgeBaseServiceImpl#computeTakeCount）：
     * 0&lt;x&lt;1 取池子的该比例；x&gt;=1 视为进上下文的绝对条数 K；x&lt;=0 不截断。
     */
    private Double topRatio = 0.5;

    public static SearchRequest of(String query) {
        SearchRequest searchRequest = new SearchRequest();
        searchRequest.query = query;
        return searchRequest;
    }

    public static SearchRequest of(String query, Integer topK) {
        SearchRequest searchRequest = of(query);
        searchRequest.topK = topK;
        return searchRequest;
    }

    /** 检索漏斗完整参数：池 N + 截断 K */
    public static SearchRequest of(String query, Integer topK, Double topRatio) {
        SearchRequest searchRequest = of(query, topK);
        searchRequest.topRatio = topRatio;
        return searchRequest;
    }
}