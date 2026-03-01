package com.bones.gateway.dto;

public record ProviderMetricsResponse(
        String provider,
        long totalRequests,
        long successRequests,
        long failedRequests,
        double successRate,
        long fallbackHits,
        double fallbackRate,
        Double ttftP50Ms,
        Double ttftP90Ms,
        Double ttftP99Ms
) {
}
