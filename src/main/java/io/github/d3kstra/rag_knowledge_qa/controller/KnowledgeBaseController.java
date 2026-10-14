package io.github.d3kstra.rag_knowledge_qa.controller;

import io.github.d3kstra.rag_knowledge_qa.lexical.LexicalIndexService;

import io.github.d3kstra.rag_knowledge_qa.dto.internal.KnowledgeSearchResult;
import io.github.d3kstra.rag_knowledge_qa.dto.request.SearchRequest;
import io.github.d3kstra.rag_knowledge_qa.dto.response.ApiResponse;
import io.github.d3kstra.rag_knowledge_qa.dto.request.FileUploadRequest;
import io.github.d3kstra.rag_knowledge_qa.knowledge.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
@Validated
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final LexicalIndexService lexicalIndexService;

    @PostMapping("/upload")
    public ApiResponse<String> uploadFile(@ModelAttribute FileUploadRequest request) {
        try {
            String documentId = knowledgeBaseService.uploadAndProcessFile(request);
            return ApiResponse.success("文件上传处理成功", documentId);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /** 重建词法索引（上传语料后调用；正常靠 TTL 自动重建） */
    @PostMapping("/lexical/rebuild")
    public ApiResponse<Integer> rebuildLexicalIndex() {
        try {
            lexicalIndexService.rebuild();
            return ApiResponse.success("词法索引重建完成", lexicalIndexService.indexedChunkCount());
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/search")
    public ApiResponse<List<KnowledgeSearchResult>> searchKnowledge(SearchRequest request) {
        try {
            List<KnowledgeSearchResult> knowledgeSearchResults = knowledgeBaseService.searchKnowledge(request);
            return ApiResponse.success("%d条结果".formatted(knowledgeSearchResults.size()), knowledgeSearchResults);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }
}