package com.bones.gateway.controller;

import com.bones.gateway.common.ApiResponse;
import com.bones.gateway.dto.CreateKnowledgeSnippetRequest;
import com.bones.gateway.dto.KnowledgeSearchResponse;
import com.bones.gateway.dto.KnowledgeSnippetItemResponse;
import com.bones.gateway.dto.UpdateKnowledgeSnippetRequest;
import com.bones.gateway.security.AccessControlService;
import com.bones.gateway.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/knowledge/snippets")
public class KnowledgeController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final AccessControlService accessControlService;

    public KnowledgeController(KnowledgeBaseService knowledgeBaseService,
                               AccessControlService accessControlService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.accessControlService = accessControlService;
    }

    @PostMapping
    public ApiResponse<KnowledgeSnippetItemResponse> createSnippet(
            @Valid @RequestBody CreateKnowledgeSnippetRequest request) {
        Long userId = accessControlService.resolveUserId(request.userId());
        return ApiResponse.success(knowledgeBaseService.createSnippet(new CreateKnowledgeSnippetRequest(
                userId,
                request.workspaceId(),
                request.title(),
                request.content(),
                request.tags()
        )));
    }

    @PutMapping("/{snippetId}")
    public ApiResponse<KnowledgeSnippetItemResponse> updateSnippet(
            @PathVariable("snippetId") Long snippetId,
            @Valid @RequestBody UpdateKnowledgeSnippetRequest request) {
        Long userId = accessControlService.resolveUserId(request.userId());
        return ApiResponse.success(knowledgeBaseService.updateSnippet(snippetId, new UpdateKnowledgeSnippetRequest(
                userId,
                request.title(),
                request.content(),
                request.tags()
        )));
    }

    @DeleteMapping("/{snippetId}")
    public ApiResponse<String> deleteSnippet(
            @PathVariable("snippetId") Long snippetId,
            @RequestParam(value = "userId", required = false) Long userId) {
        Long targetUserId = accessControlService.resolveUserId(userId);
        knowledgeBaseService.deleteSnippet(snippetId, targetUserId);
        return ApiResponse.success("deleted");
    }

    @GetMapping
    public ApiResponse<KnowledgeSearchResponse> searchSnippets(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "workspaceId", required = false) Long workspaceId,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "limit", defaultValue = "8") @Min(1) @Max(20) Integer limit) {
        Long targetUserId = accessControlService.resolveUserId(userId);
        return ApiResponse.success(knowledgeBaseService.searchSnippets(targetUserId, workspaceId, query, limit));
    }
}
