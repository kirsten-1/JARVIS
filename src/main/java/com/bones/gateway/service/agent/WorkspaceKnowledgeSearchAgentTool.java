package com.bones.gateway.service.agent;

import com.bones.gateway.dto.KnowledgeSnippetItemResponse;
import com.bones.gateway.service.KnowledgeBaseService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceKnowledgeSearchAgentTool implements AgentTool {

    public static final String NAME = "workspace_knowledge_search";

    private static final int DEFAULT_LIMIT = 3;

    private final KnowledgeBaseService knowledgeBaseService;

    public WorkspaceKnowledgeSearchAgentTool(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Search workspace knowledge snippets by query and return top matched evidence";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "Search query for workspace knowledge base"
                        ),
                        "limit", Map.of(
                                "type", "integer",
                                "minimum", 1,
                                "maximum", 10,
                                "description", "Maximum returned snippets"
                        )
                ),
                "required", List.of("query"),
                "additionalProperties", false
        );
    }

    @Override
    public String buildInput(AgentToolContext context) {
        String query = resolveQuery(context);
        int limit = resolveLimit(context);
        return "{\"workspaceId\":" + context.workspaceId() + ",\"query\":\"" + escapeJson(query) + "\",\"limit\":" + limit + "}";
    }

    @Override
    public String execute(AgentToolContext context) {
        if (context.workspaceId() == null) {
            return "workspace is null, knowledge search unavailable";
        }
        String query = resolveQuery(context);
        if (query.isBlank()) {
            return "knowledge query is empty, please provide query argument";
        }
        int limit = resolveLimit(context);
        List<KnowledgeSnippetItemResponse> items = knowledgeBaseService
                .searchSnippets(context.userId(), context.workspaceId(), query, limit)
                .items();
        if (items.isEmpty()) {
            return "no matched knowledge snippets for query=" + query;
        }

        StringBuilder output = new StringBuilder();
        output.append("query=").append(query).append(", matches=").append(items.size()).append('\n');
        for (KnowledgeSnippetItemResponse item : items) {
            output.append("- id=").append(item.id())
                    .append(", title=").append(item.title())
                    .append(", score=").append(item.relevanceScore())
                    .append(", tags=").append(item.tags())
                    .append(", snippet=").append(truncate(item.content(), 160))
                    .append('\n');
        }
        return output.toString().trim();
    }

    private String resolveQuery(AgentToolContext context) {
        Object queryFromArgs = context.toolArguments().get("query");
        if (queryFromArgs instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        Object queryFromMetadata = context.metadata().get("knowledgeQuery");
        if (queryFromMetadata instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        Object userInput = context.metadata().get("agentUserInput");
        if (userInput instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        return "";
    }

    private int resolveLimit(AgentToolContext context) {
        Object raw = context.toolArguments().get("limit");
        if (raw instanceof Number number) {
            return clampLimit(number.intValue());
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return clampLimit(Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                return DEFAULT_LIMIT;
            }
        }
        return DEFAULT_LIMIT;
    }

    private int clampLimit(int raw) {
        return Math.max(1, Math.min(raw, 10));
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

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
