package com.bones.gateway.dto;

import java.time.LocalDate;

public record DailyCostPointResponse(
        LocalDate date,
        long requests,
        long totalTokens,
        double costUsd,
        long actualRequests,
        long estimatedRequests
) {
}
