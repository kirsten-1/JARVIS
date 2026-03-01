package com.bones.gateway.integration.ai.model;

import java.util.Map;
import java.util.List;

public record AiChatRequest(
        String message,
        Long conversationId,
        Long userId,
        String provider,
        String model,
        List<AiChatMessage> messages,
        Map<String, Object> metadata
) {
}
