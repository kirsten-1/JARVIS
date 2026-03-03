package com.bones.gateway.dto;

import java.time.LocalDateTime;
import java.util.List;

public record KnowledgeSnippetItemResponse(
        Long id,
        Long workspaceId,
        Long createdBy,
        String title,
        String content,
        List<String> tags,
        Double relevanceScore,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
