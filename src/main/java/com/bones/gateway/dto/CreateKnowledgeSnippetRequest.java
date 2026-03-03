package com.bones.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record CreateKnowledgeSnippetRequest(
        Long userId,
        Long workspaceId,
        @NotBlank(message = "title must not be blank")
        String title,
        @NotBlank(message = "content must not be blank")
        String content,
        List<String> tags
) {
}
