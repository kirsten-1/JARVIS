package com.bones.gateway.service.agent;

import com.bones.gateway.config.AgentToolPolicyProperties;
import com.bones.gateway.config.AgentToolPolicyProperties.ToolPolicyProperties;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AgentToolPolicyCenter {

    public static final String METADATA_TOOL_TIMEOUT_MS = "toolTimeoutMs";
    public static final String METADATA_TOOL_MAX_RETRIES = "toolMaxRetries";
    public static final String METADATA_TOOL_IDEMPOTENCY_ENABLED = "toolIdempotencyEnabled";
    public static final String METADATA_TOOL_IDEMPOTENCY_TTL_SECONDS = "toolIdempotencyTtlSeconds";
    public static final String METADATA_TOOL_POLICIES = "toolPolicies";

    private static final int MAX_TOOL_TIMEOUT_MS = 15000;
    private static final int MAX_TOOL_MAX_RETRIES = 2;
    private static final int MAX_TOOL_IDEMPOTENCY_TTL_SECONDS = 86400;

    private final AgentToolPolicyProperties properties;

    public AgentToolPolicyCenter(AgentToolPolicyProperties properties) {
        this.properties = properties;
    }

    public ToolExecutionPolicy resolve(String toolName, Map<String, Object> metadata) {
        String normalizedToolName = normalize(toolName);

        boolean enabled = properties.isDefaultToolEnabled();
        int timeoutMs = properties.getDefaultTimeoutMs();
        int maxRetries = properties.getDefaultMaxRetries();
        boolean idempotencyEnabled = properties.isDefaultIdempotencyEnabled();
        int idempotencyTtlSeconds = properties.getDefaultIdempotencyTtlSeconds();

        if (properties.isEnabled()) {
            ToolPolicyProperties configured = properties.getTools().get(normalizedToolName);
            if (configured != null) {
                if (configured.getEnabled() != null) {
                    enabled = configured.getEnabled();
                }
                if (configured.getTimeoutMs() != null) {
                    timeoutMs = configured.getTimeoutMs();
                }
                if (configured.getMaxRetries() != null) {
                    maxRetries = configured.getMaxRetries();
                }
                if (configured.getIdempotencyEnabled() != null) {
                    idempotencyEnabled = configured.getIdempotencyEnabled();
                }
                if (configured.getIdempotencyTtlSeconds() != null) {
                    idempotencyTtlSeconds = configured.getIdempotencyTtlSeconds();
                }
            }
        }

        if (metadata != null && !metadata.isEmpty()) {
            timeoutMs = readInt(metadata.get(METADATA_TOOL_TIMEOUT_MS), timeoutMs);
            maxRetries = readInt(metadata.get(METADATA_TOOL_MAX_RETRIES), maxRetries);
            idempotencyEnabled = readBoolean(metadata.get(METADATA_TOOL_IDEMPOTENCY_ENABLED), idempotencyEnabled);
            idempotencyTtlSeconds = readInt(metadata.get(METADATA_TOOL_IDEMPOTENCY_TTL_SECONDS), idempotencyTtlSeconds);

            Object perToolPolicies = metadata.get(METADATA_TOOL_POLICIES);
            if (perToolPolicies instanceof Map<?, ?> policyMap) {
                Object perTool = policyMap.get(normalizedToolName);
                if (perTool instanceof Map<?, ?> perToolConfig) {
                    enabled = readBoolean(perToolConfig.get("enabled"), enabled);
                    timeoutMs = readInt(perToolConfig.get("timeoutMs"), timeoutMs);
                    maxRetries = readInt(perToolConfig.get("maxRetries"), maxRetries);
                    idempotencyEnabled = readBoolean(perToolConfig.get("idempotencyEnabled"), idempotencyEnabled);
                    idempotencyTtlSeconds = readInt(perToolConfig.get("idempotencyTtlSeconds"), idempotencyTtlSeconds);
                }
            }
        }

        int safeTimeout = clamp(timeoutMs, 200, MAX_TOOL_TIMEOUT_MS);
        int safeRetries = clamp(maxRetries, 0, MAX_TOOL_MAX_RETRIES);
        int safeTtlSeconds = clamp(idempotencyTtlSeconds, 60, MAX_TOOL_IDEMPOTENCY_TTL_SECONDS);
        return new ToolExecutionPolicy(enabled, safeTimeout, safeRetries, idempotencyEnabled, safeTtlSeconds);
    }

    private String normalize(String toolName) {
        if (toolName == null) {
            return "";
        }
        return toolName.trim().toLowerCase(Locale.ROOT);
    }

    private int readInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private boolean readBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text.trim());
        }
        return defaultValue;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    public record ToolExecutionPolicy(
            boolean enabled,
            int timeoutMs,
            int maxRetries,
            boolean idempotencyEnabled,
            int idempotencyTtlSeconds
    ) {
    }
}
