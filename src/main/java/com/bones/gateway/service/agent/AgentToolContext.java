package com.bones.gateway.service.agent;

import java.util.Map;

public record AgentToolContext(
        Long conversationId,
        Long userId,
        Long workspaceId,
        Map<String, Object> metadata,
        String toolCallId,
        Map<String, Object> toolArguments
) {

    public AgentToolContext(Long conversationId,
                            Long userId,
                            Long workspaceId,
                            Map<String, Object> metadata) {
        this(conversationId, userId, workspaceId, metadata, null, Map.of());
    }
}
