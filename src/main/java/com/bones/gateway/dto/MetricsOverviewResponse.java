package com.bones.gateway.dto;

import java.time.LocalDate;
import java.util.List;

public record MetricsOverviewResponse(
        Long workspaceId,
        LocalDate dateFrom,
        LocalDate dateTo,
        long totalRequests,
        long successRequests,
        long failedRequests,
        double successRate,
        long fallbackHits,
        double fallbackRate,
        Double ttftP50Ms,
        Double ttftP90Ms,
        Double ttftP99Ms,
        List<DailyCostPointResponse> costTrend
) {
}
