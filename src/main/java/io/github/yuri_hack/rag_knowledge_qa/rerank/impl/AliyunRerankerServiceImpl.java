package io.github.yuri_hack.rag_knowledge_qa.rerank.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.github.yuri_hack.rag_knowledge_qa.dto.internal.KnowledgeSearchResult;
import io.github.yuri_hack.rag_knowledge_qa.dto.internal.RerankResult;
import io.github.yuri_hack.rag_knowledge_qa.rerank.RerankerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AliyunRerankerServiceImpl implements RerankerService {

    @Value("${aliyun.opensearch.api-key}")
    private String apiKey;

    private static final String API_URL = "http://default-05lk.platform-cn-shanghai.opensearch.aliyuncs.com/v3/openapi/workspaces/default/ranker/ops-bge-reranker-larger";
    private static final int MAX_RERANK_DOCUMENTS = 50;

    private final RestTemplate restTemplate;

    public AliyunRerankerServiceImpl() {
        this.restTemplate = new RestTemplate();
    }

    /**
     * 对知识搜索结果进行重排序
     */
    @Override
    public List<RerankResult> rerankKnowledgeResults(String query, List<KnowledgeSearchResult> knowledgeResults) {
        if (knowledgeResults == null || knowledgeResults.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            // 限制重排序的文档数量以避免API限制
            List<KnowledgeSearchResult> documentsToRerank = knowledgeResults.stream()
                    .limit(MAX_RERANK_DOCUMENTS)
                    .collect(Collectors.toList());

            List<String> documentContents = documentsToRerank.stream()
                    .map(KnowledgeSearchResult::getContent)
                    .collect(Collectors.toList());

            List<Double> rerankScores = callRerankerApi(query, documentContents);

            return buildRerankResults(documentsToRerank, rerankScores);

        } catch (Exception e) {
            log.warn("重排序服务调用失败，返回原始排序结果: {}", e.getMessage());
            return convertToRerankResults(knowledgeResults);
        }
    }

    /**
     * 调用阿里云重排序API
     */
    private List<Double> callRerankerApi(String query, List<String> documents) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("阿里云API密钥未配置");
        }

        Map<String, Object> requestBody = buildRequestBody(query, documents);
        HttpEntity<Map<String, Object>> requestEntity = buildRequestEntity(requestBody);

        log.debug("调用阿里云重排序API，查询: {}, 文档数量: {}", query, documents.size());

        ResponseEntity<String> response = restTemplate.exchange(
                API_URL, HttpMethod.POST, requestEntity, String.class);

        return parseRerankerResponse(response.getBody());
    }

    /**
     * 构建请求体
     */
    private Map<String, Object> buildRequestBody(String query, List<String> documents) {
        Map<String, Object> body = new HashMap<>();
        // 直接添加顶级字段：query 和 docs（与 curl 请求体结构完全一致）
        body.put("query", query);
        body.put("docs", documents);
        return body;
    }

    /**
     * 构建请求实体
     */
    private HttpEntity<Map<String, Object>> buildRequestEntity(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);
        return new HttpEntity<>(body, headers);
    }

    /**
     * 解析重排序API响应
     */
    private List<Double> parseRerankerResponse(String responseBody) {
        JSONObject jsonResponse = JSONObject.parseObject(responseBody);

        if (!jsonResponse.containsKey("result")) {
            throw new RuntimeException("重排序API响应格式错误: 缺少result字段");
        }

        JSONObject result = jsonResponse.getJSONObject("result");
        if (result == null) {
            throw new RuntimeException("重排序结果为空");
        }
        JSONArray rerankScores = result.getJSONArray("scores");

        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < rerankScores.size(); i++) {
            JSONObject rerankScore = rerankScores.getJSONObject(i);
            scores.add(rerankScore.getDouble("score"));
        }
        return scores;
    }

    /**
     * 构建重排序结果
     */
    private List<RerankResult> buildRerankResults(List<KnowledgeSearchResult> knowledgeResults,
                                                  List<Double> rerankScores) {
        List<RerankResult> rerankResults = new ArrayList<>();

        for (int i = 0; i < knowledgeResults.size(); i++) {
            KnowledgeSearchResult knowledgeResult = knowledgeResults.get(i);
            Double rerankScore = rerankScores.get(i);

            rerankResults.add(RerankResult.from(knowledgeResult, rerankScore));
        }

        // 按重排序分数降序排列
        rerankResults.sort((a, b) -> Double.compare(b.getRerankScore(), a.getRerankScore()));

        log.debug("重排序完成，处理了 {} 个文档", rerankResults.size());
        return rerankResults;
    }

    /**
     * 将知识搜索结果转换为重排序结果（默认情况）
     */
    private List<RerankResult> convertToRerankResults(List<KnowledgeSearchResult> knowledgeResults) {
        return knowledgeResults.stream()
                .map(result -> RerankResult.from(result, result.getSimilarity()))
                .collect(Collectors.toList());
    }
}