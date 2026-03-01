package com.bones.gateway.dto;

public record CreateConversationRequest(
        Long userId,
        Long workspaceId,
        String title
) {
}
