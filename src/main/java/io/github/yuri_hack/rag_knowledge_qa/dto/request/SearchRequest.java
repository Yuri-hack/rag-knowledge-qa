package io.github.yuri_hack.rag_knowledge_qa.dto.request;

import lombok.Data;

@Data
public class SearchRequest {
    private String query;

    private Integer topK = 10;

    private Double topRatio = 0.5;

    public static SearchRequest of(String query) {
        SearchRequest searchRequest = new SearchRequest();
        searchRequest.query = query;
        return searchRequest;
    }
}