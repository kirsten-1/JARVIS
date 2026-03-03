package com.bones.gateway.dto;

import java.time.LocalDateTime;

public record KnowledgeRetrievalPolicyResponse(
        Long workspaceId,
        String mode,
        Double keywordWeight,
        Double vectorWeight,
        Double hybridMinScore,
        Integer maxCandidates,
        Long updatedBy,
        LocalDateTime updatedAt
) {
}
