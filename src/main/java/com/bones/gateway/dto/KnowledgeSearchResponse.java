package com.bones.gateway.dto;

import java.util.List;

public record KnowledgeSearchResponse(
        Long workspaceId,
        String query,
        String searchMode,
        int limit,
        int maxCandidates,
        boolean overrideApplied,
        String overrideSource,
        Double keywordWeight,
        Double vectorWeight,
        Double scoreThreshold,
        List<KnowledgeSnippetItemResponse> items
) {
}
