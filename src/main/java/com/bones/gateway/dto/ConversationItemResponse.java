package com.bones.gateway.dto;

import com.bones.gateway.entity.ConversationStatus;
import java.time.LocalDateTime;

public record ConversationItemResponse(
        Long id,
        Long userId,
        Long workspaceId,
        String title,
        ConversationStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
