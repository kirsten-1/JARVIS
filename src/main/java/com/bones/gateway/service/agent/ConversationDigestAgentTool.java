package com.bones.gateway.service.agent;

import com.bones.gateway.dto.MessageItemResponse;
import com.bones.gateway.service.MessageService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ConversationDigestAgentTool implements AgentTool {

    public static final String NAME = "conversation_digest";

    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 20;
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
    public String description() {
        return "Summarize recent conversation history as role-content digest lines";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "limit", Map.of(
                                "type", "integer",
                                "minimum", 1,
                                "maximum", MAX_LIMIT,
                                "description", "Number of latest messages to summarize"
                        )
                ),
                "required", java.util.List.of(),
                "additionalProperties", false
        );
    }

    @Override
    public String buildInput(AgentToolContext context) {
        int limit = resolveLimit(context);
        return "{\"conversationId\":" + context.conversationId() + ",\"limit\":" + limit + "}";
    }

    @Override
    public String execute(AgentToolContext context) {
        List<MessageItemResponse> all = messageService.listMessages(context.conversationId(), context.userId());
        if (all.isEmpty()) {
            return "no historical messages";
        }
        int limit = resolveLimit(context);
        int fromIndex = Math.max(0, all.size() - limit);
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

    private int resolveLimit(AgentToolContext context) {
        Map<String, Object> arguments = context.toolArguments();
        if (arguments != null) {
            Object limit = arguments.get("limit");
            if (limit instanceof Number number) {
                return clampLimit(number.intValue());
            }
            if (limit instanceof String text) {
                try {
                    return clampLimit(Integer.parseInt(text.trim()));
                } catch (NumberFormatException ignored) {
                    return DEFAULT_LIMIT;
                }
            }
        }
        return DEFAULT_LIMIT;
    }

    private int clampLimit(int raw) {
        return Math.max(1, Math.min(raw, MAX_LIMIT));
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
