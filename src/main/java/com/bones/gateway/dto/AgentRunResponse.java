package com.bones.gateway.dto;

import java.util.List;

public record AgentRunResponse(
        Long conversationId,
        Long userMessageId,
        Long assistantMessageId,
        String assistantContent,
        String model,
        String finishReason,
        List<AgentStepResponse> steps
) {
}
