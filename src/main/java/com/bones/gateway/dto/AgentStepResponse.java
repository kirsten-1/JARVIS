package com.bones.gateway.dto;

public record AgentStepResponse(
        int index,
        String tool,
        String status,
        String input,
        String output,
        long durationMs
) {
}
