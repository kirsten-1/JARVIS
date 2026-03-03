package com.bones.gateway.service.agent;

import com.bones.gateway.dto.KnowledgeSearchResponse;
import com.bones.gateway.dto.KnowledgeSnippetItemResponse;
import com.bones.gateway.service.KnowledgeBaseService;
import com.bones.gateway.service.KnowledgeRetrievalPolicyService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceKnowledgeSearchAgentTool implements AgentTool {

    public static final String NAME = "workspace_knowledge_search";

    private static final int DEFAULT_LIMIT = 3;

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeRetrievalPolicyService knowledgeRetrievalPolicyService;

    public WorkspaceKnowledgeSearchAgentTool(KnowledgeBaseService knowledgeBaseService,
                                             KnowledgeRetrievalPolicyService knowledgeRetrievalPolicyService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeRetrievalPolicyService = knowledgeRetrievalPolicyService;
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
                        ),
                        "searchMode", Map.of(
                                "type", "string",
                                "enum", List.of("keyword", "vector", "hybrid"),
                                "description", "Retrieval mode. Default is hybrid"
                        ),
                        "vectorMinSimilarity", Map.of(
                                "type", "number",
                                "minimum", 0,
                                "maximum", 1,
                                "description", "Vector mode min similarity threshold override"
                        ),
                        "hybridMinScore", Map.of(
                                "type", "number",
                                "minimum", 0,
                                "maximum", 1,
                                "description", "Hybrid mode min final score threshold override"
                        ),
                        "hybridKeywordWeight", Map.of(
                                "type", "number",
                                "minimum", 0,
                                "description", "Hybrid keyword weight override"
                        ),
                        "hybridVectorWeight", Map.of(
                                "type", "number",
                                "minimum", 0,
                                "description", "Hybrid vector weight override"
                        ),
                        "maxCandidates", Map.of(
                                "type", "integer",
                                "minimum", 1,
                                "maximum", 200,
                                "description", "Search candidate size override before ranking"
                        ),
                        "autoTune", Map.of(
                                "type", "boolean",
                                "description", "Use retrieval feedback recommendation when overrides are not provided"
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
        String searchMode = resolveSearchMode(context);
        boolean autoTune = resolveAutoTune(context);
        SearchPolicyResolution policyResolution = resolveSearchPolicy(context, autoTune);
        KnowledgeBaseService.SearchOverrides overrides = policyResolution.overrides();
        StringBuilder input = new StringBuilder();
        input.append("{\"workspaceId\":").append(context.workspaceId())
                .append(",\"query\":\"").append(escapeJson(query))
                .append("\",\"searchMode\":\"").append(escapeJson(searchMode))
                .append("\",\"limit\":").append(limit);
        input.append(",\"autoTune\":").append(autoTune);
        input.append(",\"overrideSource\":\"").append(escapeJson(policyResolution.overrideSource())).append("\"");
        if (overrides != null && overrides.vectorMinSimilarity() != null) {
            input.append(",\"vectorMinSimilarity\":").append(overrides.vectorMinSimilarity());
        }
        if (overrides != null && overrides.hybridMinScore() != null) {
            input.append(",\"hybridMinScore\":").append(overrides.hybridMinScore());
        }
        if (overrides != null && overrides.hybridKeywordWeight() != null) {
            input.append(",\"hybridKeywordWeight\":").append(overrides.hybridKeywordWeight());
        }
        if (overrides != null && overrides.hybridVectorWeight() != null) {
            input.append(",\"hybridVectorWeight\":").append(overrides.hybridVectorWeight());
        }
        if (overrides != null && overrides.maxCandidates() != null) {
            input.append(",\"maxCandidates\":").append(overrides.maxCandidates());
        }
        input.append("}");
        return input.toString();
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
        String searchMode = resolveSearchMode(context);
        boolean autoTune = resolveAutoTune(context);
        SearchPolicyResolution policyResolution = resolveSearchPolicy(context, autoTune);
        KnowledgeBaseService.SearchOverrides overrides = policyResolution.overrides();
        KnowledgeSearchResponse searchResponse = knowledgeBaseService
                .searchSnippets(
                        context.userId(),
                        context.workspaceId(),
                        query,
                        limit,
                        searchMode,
                        overrides,
                        policyResolution.overrideSource()
                );
        List<KnowledgeSnippetItemResponse> items = searchResponse.items();
        if (items.isEmpty()) {
            return "no matched knowledge snippets for query=" + query;
        }

        StringBuilder output = new StringBuilder();
        output.append("query=").append(query)
                .append(", searchMode=").append(searchResponse.searchMode())
                .append(", matches=").append(items.size())
                .append(", maxCandidates=").append(searchResponse.maxCandidates())
                .append(", overrideApplied=").append(searchResponse.overrideApplied())
                .append(", overrideSource=").append(searchResponse.overrideSource())
                .append(", autoTune=").append(autoTune)
                .append(", keywordWeight=").append(searchResponse.keywordWeight())
                .append(", vectorWeight=").append(searchResponse.vectorWeight())
                .append(", scoreThreshold=").append(searchResponse.scoreThreshold())
                .append('\n');
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

    private String resolveSearchMode(AgentToolContext context) {
        Object raw = context.toolArguments().get("searchMode");
        if (raw instanceof String text && !text.isBlank()) {
            return normalizeSearchMode(text);
        }
        Object modeFromMetadata = context.metadata().get("knowledgeSearchMode");
        if (modeFromMetadata instanceof String text && !text.isBlank()) {
            return normalizeSearchMode(text);
        }
        return "hybrid";
    }

    private SearchPolicyResolution resolveSearchPolicy(AgentToolContext context, boolean autoTune) {
        Double vectorMinSimilarity = resolveDoubleOption(
                context,
                "vectorMinSimilarity",
                "knowledgeVectorMinSimilarity"
        );
        Double hybridMinScore = resolveDoubleOption(
                context,
                "hybridMinScore",
                "knowledgeHybridMinScore"
        );
        Double hybridKeywordWeight = resolveDoubleOption(
                context,
                "hybridKeywordWeight",
                "knowledgeHybridKeywordWeight"
        );
        Double hybridVectorWeight = resolveDoubleOption(
                context,
                "hybridVectorWeight",
                "knowledgeHybridVectorWeight"
        );
        Integer maxCandidates = resolveIntegerOption(
                context,
                "maxCandidates",
                "knowledgeMaxCandidates"
        );
        KnowledgeBaseService.SearchOverrides overrides = new KnowledgeBaseService.SearchOverrides(
                vectorMinSimilarity,
                hybridMinScore,
                hybridKeywordWeight,
                hybridVectorWeight,
                maxCandidates
        );
        if (overrides.hasAny()) {
            return new SearchPolicyResolution(overrides, "request_override");
        }
        if (!autoTune) {
            return new SearchPolicyResolution(null, "none");
        }
        KnowledgeRetrievalPolicyService.AutoTuneDecision autoTuneDecision =
                knowledgeRetrievalPolicyService.resolveAutoTuneDecision(context.userId(), context.workspaceId());
        return new SearchPolicyResolution(autoTuneDecision.overrides(), autoTuneDecision.overrideSource());
    }

    private boolean resolveAutoTune(AgentToolContext context) {
        Object raw = context.toolArguments().get("autoTune");
        Boolean fromArg = parseBoolean(raw);
        if (fromArg != null) {
            return fromArg;
        }
        Object fromMetadata = context.metadata().get("knowledgeAutoTune");
        Boolean parsedFromMetadata = parseBoolean(fromMetadata);
        return parsedFromMetadata != null && parsedFromMetadata;
    }

    private Double resolveDoubleOption(AgentToolContext context, String argName, String metadataKey) {
        Object fromArg = context.toolArguments().get(argName);
        Double parsedFromArg = parseDouble(fromArg);
        if (parsedFromArg != null) {
            return parsedFromArg;
        }
        Object fromMetadataMap = resolveMetadataMapValue(context, argName);
        Double parsedFromMetadataMap = parseDouble(fromMetadataMap);
        if (parsedFromMetadataMap != null) {
            return parsedFromMetadataMap;
        }
        return parseDouble(context.metadata().get(metadataKey));
    }

    private Integer resolveIntegerOption(AgentToolContext context, String argName, String metadataKey) {
        Object fromArg = context.toolArguments().get(argName);
        Integer parsedFromArg = parseInteger(fromArg);
        if (parsedFromArg != null) {
            return parsedFromArg;
        }
        Object fromMetadataMap = resolveMetadataMapValue(context, argName);
        Integer parsedFromMetadataMap = parseInteger(fromMetadataMap);
        if (parsedFromMetadataMap != null) {
            return parsedFromMetadataMap;
        }
        return parseInteger(context.metadata().get(metadataKey));
    }

    private Object resolveMetadataMapValue(AgentToolContext context, String key) {
        Object raw = context.metadata().get("knowledgeSearchOverrides");
        if (raw instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }

    private Double parseDouble(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer parseInteger(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Boolean parseBoolean(Object raw) {
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof String text && !text.isBlank()) {
            String normalized = text.trim().toLowerCase();
            if ("true".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized)) {
                return false;
            }
        }
        return null;
    }

    private String normalizeSearchMode(String raw) {
        String normalized = raw.trim().toLowerCase();
        if ("keyword".equals(normalized) || "lexical".equals(normalized)) {
            return "keyword";
        }
        if ("vector".equals(normalized) || "semantic".equals(normalized)) {
            return "vector";
        }
        return "hybrid";
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

    private record SearchPolicyResolution(
            KnowledgeBaseService.SearchOverrides overrides,
            String overrideSource
    ) {
    }
}
