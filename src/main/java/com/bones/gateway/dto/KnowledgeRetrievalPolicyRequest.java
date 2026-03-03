package com.bones.gateway.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record KnowledgeRetrievalPolicyRequest(
        Long userId,
        Long workspaceId,
        @NotBlank String mode,
        @DecimalMin("0.0") @DecimalMax("1.0") Double keywordWeight,
        @DecimalMin("0.0") @DecimalMax("1.0") Double vectorWeight,
        @DecimalMin("0.0") @DecimalMax("1.0") Double hybridMinScore,
        @Min(1) @Max(200) Integer maxCandidates
) {
}
