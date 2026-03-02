package com.bones.gateway.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record AgentRunRequest(
        Long userId,
        @NotBlank(message = "message must not be blank")
        String message,
        String provider,
        String model,
        @Min(value = 1, message = "maxSteps must be >= 1")
        @Max(value = 6, message = "maxSteps must be <= 6")
        Integer maxSteps,
        Map<String, Object> metadata
) {
}
