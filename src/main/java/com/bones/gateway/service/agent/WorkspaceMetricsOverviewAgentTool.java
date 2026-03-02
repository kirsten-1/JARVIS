package com.bones.gateway.service.agent;

import com.bones.gateway.dto.MetricsOverviewResponse;
import com.bones.gateway.service.OpsMetricsService;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceMetricsOverviewAgentTool implements AgentTool {

    public static final String NAME = "workspace_metrics_overview";

    private final OpsMetricsService opsMetricsService;

    public WorkspaceMetricsOverviewAgentTool(OpsMetricsService opsMetricsService) {
        this.opsMetricsService = opsMetricsService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Get workspace-level operation metrics overview for the last 7 days";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "workspaceId", Map.of("type", "integer", "description", "Optional workspace id override")
                ),
                "required", java.util.List.of(),
                "additionalProperties", false
        );
    }

    @Override
    public String buildInput(AgentToolContext context) {
        Long workspaceId = resolveWorkspaceId(context);
        return "{\"workspaceId\":" + workspaceId + ",\"range\":\"last_7_days\"}";
    }

    @Override
    public String execute(AgentToolContext context) {
        Long workspaceId = resolveWorkspaceId(context);
        if (workspaceId == null) {
            return "workspace is null, metrics unavailable";
        }
        MetricsOverviewResponse overview = opsMetricsService.getOverview(workspaceId, null, null);
        return "workspaceId=" + overview.workspaceId()
                + ", totalRequests=" + overview.totalRequests()
                + ", successRate=" + overview.successRate()
                + ", fallbackRate=" + overview.fallbackRate()
                + ", ttftP50Ms=" + overview.ttftP50Ms()
                + ", ttftP90Ms=" + overview.ttftP90Ms();
    }

    private Long resolveWorkspaceId(AgentToolContext context) {
        Map<String, Object> arguments = context.toolArguments();
        if (arguments != null) {
            Object workspaceId = arguments.get("workspaceId");
            if (workspaceId instanceof Number number) {
                return number.longValue();
            }
            if (workspaceId instanceof String text) {
                try {
                    return Long.parseLong(text.trim());
                } catch (NumberFormatException ignored) {
                    return context.workspaceId();
                }
            }
        }
        return context.workspaceId();
    }
}
