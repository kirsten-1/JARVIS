package com.bones.gateway.integration.ai.model;

public record AiChatMessage(
        String role,
        String content
) {
}
