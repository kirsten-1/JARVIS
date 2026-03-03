package com.bones.gateway.dto;

import java.time.LocalDate;

public record KnowledgeRetrievalFeedbackResponse(
        Long workspaceId,
        LocalDate date,
        boolean helpful,
        String searchMode
) {
}
