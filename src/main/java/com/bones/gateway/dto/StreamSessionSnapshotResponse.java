package com.bones.gateway.dto;

public record StreamSessionSnapshotResponse(
        String streamId,
        Long conversationId,
        Long assistantMessageId,
        String status,
        String content,
        String updatedAt,
        String error
) {
}
