package com.bones.gateway.integration.ai.model;

import java.util.List;

public record AiChatResponse(
        String content,
        String model,
        String finishReason,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        List<AiToolCall> toolCalls
) {

    public AiChatResponse(String content, String model, String finishReason) {
        this(content, model, finishReason, null, null, null, List.of());
    }

    public AiChatResponse(String content,
                          String model,
                          String finishReason,
                          Integer promptTokens,
                          Integer completionTokens,
                          Integer totalTokens) {
        this(content, model, finishReason, promptTokens, completionTokens, totalTokens, List.of());
    }
}
