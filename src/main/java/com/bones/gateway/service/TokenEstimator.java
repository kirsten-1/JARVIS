package com.bones.gateway.service;

import com.bones.gateway.integration.ai.model.AiChatMessage;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TokenEstimator {

    public int estimateTextTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int chars = text.codePointCount(0, text.length());
        return Math.max(1, (int) Math.ceil(chars / 4.0));
    }

    public int estimateMessagesTokens(List<AiChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        return messages.stream().mapToInt(message -> estimateTextTokens(message.content())).sum();
    }
}
