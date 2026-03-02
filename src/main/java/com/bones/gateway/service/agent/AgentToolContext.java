package com.bones.gateway.service.agent;

import java.util.Map;

public record AgentToolContext(
        Long conversationId,
        Long userId,
        Long workspaceId,
        Map<String, Object> metadata
) {
}
