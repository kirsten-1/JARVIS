package com.bones.gateway.integration.ai.model;

public record AiChatResponse(
        String content,
        String model,
        String finishReason,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {

    public AiChatResponse(String content, String model, String finishReason) {
        this(content, model, finishReason, null, null, null);
    }
}
