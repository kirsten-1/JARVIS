package com.bones.gateway.dto;

public record ConversationChatResponse(
        Long conversationId,
        Long userMessageId,
        Long assistantMessageId,
        String assistantContent,
        String model,
        String finishReason
) {
}
