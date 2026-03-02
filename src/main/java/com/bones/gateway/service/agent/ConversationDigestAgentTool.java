package com.bones.gateway.service.agent;

import com.bones.gateway.dto.MessageItemResponse;
import com.bones.gateway.service.MessageService;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ConversationDigestAgentTool implements AgentTool {

    public static final String NAME = "conversation_digest";

    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_OUTPUT_CHARS = 1200;
    private static final int MAX_ITEM_CHARS = 120;

    private final MessageService messageService;

    public ConversationDigestAgentTool(MessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String buildInput(AgentToolContext context) {
        return "{\"conversationId\":" + context.conversationId() + ",\"limit\":" + DEFAULT_LIMIT + "}";
    }

    @Override
    public String execute(AgentToolContext context) {
        List<MessageItemResponse> all = messageService.listMessages(context.conversationId(), context.userId());
        if (all.isEmpty()) {
            return "no historical messages";
        }
        int fromIndex = Math.max(0, all.size() - DEFAULT_LIMIT);
        List<MessageItemResponse> tail = all.subList(fromIndex, all.size());
        StringBuilder digest = new StringBuilder();
        for (MessageItemResponse item : tail) {
            String role = item.role() == null ? "unknown" : item.role().name();
            digest.append(role)
                    .append(": ")
                    .append(truncate(item.content(), MAX_ITEM_CHARS))
                    .append('\n');
        }
        return truncate(digest.toString().trim(), MAX_OUTPUT_CHARS);
    }

    private String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...";
    }
}
