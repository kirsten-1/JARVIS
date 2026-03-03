package com.bones.gateway.dto;

import java.util.List;

public record KnowledgeSearchResponse(
        Long workspaceId,
        String query,
        int limit,
        List<KnowledgeSnippetItemResponse> items
) {
}
