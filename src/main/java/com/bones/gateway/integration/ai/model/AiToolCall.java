package com.bones.gateway.integration.ai.model;

public record AiToolCall(
        String id,
        String name,
        String argumentsJson
) {
}
