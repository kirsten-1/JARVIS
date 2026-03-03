package com.bones.gateway.controller;

import com.bones.gateway.common.ApiResponse;
import com.bones.gateway.dto.KnowledgeRetrievalFeedbackRequest;
import com.bones.gateway.dto.KnowledgeRetrievalFeedbackResponse;
import com.bones.gateway.dto.KnowledgeRetrievalRecommendationResponse;
import com.bones.gateway.security.AccessControlService;
import com.bones.gateway.service.KnowledgeRetrievalPolicyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/knowledge/retrieval")
public class KnowledgeRetrievalController {

    private final AccessControlService accessControlService;
    private final KnowledgeRetrievalPolicyService knowledgeRetrievalPolicyService;

    public KnowledgeRetrievalController(AccessControlService accessControlService,
                                        KnowledgeRetrievalPolicyService knowledgeRetrievalPolicyService) {
        this.accessControlService = accessControlService;
        this.knowledgeRetrievalPolicyService = knowledgeRetrievalPolicyService;
    }

    @PostMapping("/feedback")
    public ApiResponse<KnowledgeRetrievalFeedbackResponse> submitFeedback(
            @Valid @RequestBody KnowledgeRetrievalFeedbackRequest request) {
        Long userId = accessControlService.resolveUserId(request.userId());
        return ApiResponse.success(knowledgeRetrievalPolicyService.recordFeedback(userId, request));
    }

    @GetMapping("/recommendation")
    public ApiResponse<KnowledgeRetrievalRecommendationResponse> getRecommendation(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "workspaceId", required = false) Long workspaceId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "minSamples", required = false)
            @Min(1) @Max(500) Integer minSamples) {
        Long targetUserId = accessControlService.resolveUserId(userId);
        return ApiResponse.success(knowledgeRetrievalPolicyService.getRecommendation(
                targetUserId,
                workspaceId,
                from,
                to,
                minSamples
        ));
    }
}
