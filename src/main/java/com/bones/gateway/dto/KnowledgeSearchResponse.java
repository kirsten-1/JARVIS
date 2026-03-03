package com.bones.gateway.dto;

import java.util.List;

public record KnowledgeSearchResponse(
        Long workspaceId,
        String query,
        String searchMode,
        int limit,
        List<KnowledgeSnippetItemResponse> items
) {
}
