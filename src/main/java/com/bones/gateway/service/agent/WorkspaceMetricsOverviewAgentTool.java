package com.bones.gateway.service.agent;

import com.bones.gateway.dto.MetricsOverviewResponse;
import com.bones.gateway.service.OpsMetricsService;
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
    public String buildInput(AgentToolContext context) {
        return "{\"workspaceId\":" + context.workspaceId() + ",\"range\":\"last_7_days\"}";
    }

    @Override
    public String execute(AgentToolContext context) {
        if (context.workspaceId() == null) {
            return "workspace is null, metrics unavailable";
        }
        MetricsOverviewResponse overview = opsMetricsService.getOverview(context.workspaceId(), null, null);
        return "workspaceId=" + overview.workspaceId()
                + ", totalRequests=" + overview.totalRequests()
                + ", successRate=" + overview.successRate()
                + ", fallbackRate=" + overview.fallbackRate()
                + ", ttftP50Ms=" + overview.ttftP50Ms()
                + ", ttftP90Ms=" + overview.ttftP90Ms();
    }
}
