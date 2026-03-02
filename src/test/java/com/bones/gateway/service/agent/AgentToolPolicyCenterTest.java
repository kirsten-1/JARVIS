package com.bones.gateway.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bones.gateway.config.AgentToolPolicyProperties;
import com.bones.gateway.config.AgentToolPolicyProperties.ToolPolicyProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentToolPolicyCenterTest {

    @Test
    void resolve_shouldReturnDefaultsWhenNoOverrides() {
        AgentToolPolicyProperties properties = new AgentToolPolicyProperties();
        AgentToolPolicyCenter center = new AgentToolPolicyCenter(properties);

        AgentToolPolicyCenter.ToolExecutionPolicy policy = center.resolve("time_now", null);

        assertTrue(policy.enabled());
        assertEquals(2000, policy.timeoutMs());
        assertEquals(0, policy.maxRetries());
        assertTrue(policy.idempotencyEnabled());
        assertEquals(1800, policy.idempotencyTtlSeconds());
    }

    @Test
    void resolve_shouldApplyToolConfigAndMetadataOverrides() {
        AgentToolPolicyProperties properties = new AgentToolPolicyProperties();
        ToolPolicyProperties tool = new ToolPolicyProperties();
        tool.setEnabled(false);
        tool.setTimeoutMs(3000);
        tool.setMaxRetries(1);
        tool.setIdempotencyEnabled(false);
        tool.setIdempotencyTtlSeconds(7200);
        properties.setTools(Map.of("workspace_metrics_overview", tool));

        AgentToolPolicyCenter center = new AgentToolPolicyCenter(properties);

        AgentToolPolicyCenter.ToolExecutionPolicy policy = center.resolve(
                "workspace_metrics_overview",
                Map.of(
                        "toolTimeoutMs", 5000,
                        "toolMaxRetries", 2,
                        "toolIdempotencyEnabled", true,
                        "toolIdempotencyTtlSeconds", 1200
                )
        );

        assertFalse(policy.enabled());
        assertEquals(5000, policy.timeoutMs());
        assertEquals(2, policy.maxRetries());
        assertTrue(policy.idempotencyEnabled());
        assertEquals(1200, policy.idempotencyTtlSeconds());
    }

    @Test
    void resolve_shouldApplyPerRequestPerToolPolicyFromMetadata() {
        AgentToolPolicyProperties properties = new AgentToolPolicyProperties();
        AgentToolPolicyCenter center = new AgentToolPolicyCenter(properties);

        AgentToolPolicyCenter.ToolExecutionPolicy policy = center.resolve(
                "conversation_digest",
                Map.of(
                        "toolPolicies", Map.of(
                                "conversation_digest", Map.of(
                                        "enabled", false,
                                        "timeoutMs", 600,
                                        "maxRetries", 1,
                                        "idempotencyEnabled", false,
                                        "idempotencyTtlSeconds", 600
                                )
                        )
                )
        );

        assertFalse(policy.enabled());
        assertEquals(600, policy.timeoutMs());
        assertEquals(1, policy.maxRetries());
        assertFalse(policy.idempotencyEnabled());
        assertEquals(600, policy.idempotencyTtlSeconds());
    }
}
