package com.bones.gateway.dto;

import com.bones.gateway.entity.MessageRole;
import java.time.LocalDateTime;

public record MessageItemResponse(
        Long id,
        Long conversationId,
        MessageRole role,
        String content,
        Integer tokenCount,
        LocalDateTime createdAt
) {
}
