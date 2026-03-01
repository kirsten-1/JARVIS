package com.bones.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record ConversationChatRequest(
        Long userId,
        @NotBlank(message = "message must not be blank")
        String message,
        String provider,
        String model,
        Map<String, Object> metadata
) {
}
