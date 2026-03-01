package com.bones.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record AiChatRequestDto(
        @NotBlank(message = "message must not be blank")
        String message,
        Long conversationId,
        Long userId,
        String provider,
        String model,
        Map<String, Object> metadata
) {
}
