package com.bones.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record UpdateKnowledgeSnippetRequest(
        Long userId,
        @NotBlank(message = "title must not be blank")
        String title,
        @NotBlank(message = "content must not be blank")
        String content,
        List<String> tags
) {
}
