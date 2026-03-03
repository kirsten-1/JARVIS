package com.bones.gateway.dto;

import java.time.LocalDate;

public record KnowledgeRetrievalRecommendationResponse(
        Long workspaceId,
        LocalDate from,
        LocalDate to,
        int minSamples,
        long feedbackCount,
        long helpfulCount,
        double helpfulRate,
        String decision,
        Double suggestedKeywordWeight,
        Double suggestedVectorWeight,
        Double suggestedHybridMinScore,
        Integer suggestedMaxCandidates
) {
}
