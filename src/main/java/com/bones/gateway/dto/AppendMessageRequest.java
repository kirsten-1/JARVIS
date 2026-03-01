package com.bones.gateway.dto;

import com.bones.gateway.entity.MessageRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AppendMessageRequest(
        Long userId,
        @NotNull(message = "role must not be null")
        MessageRole role,
        @NotBlank(message = "content must not be blank")
        String content,
        Integer tokenCount
) {
}
