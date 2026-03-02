package com.bones.gateway.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jarvis.agent.tool-policy")
public class AgentToolPolicyProperties {

    private boolean enabled = true;
    private int defaultTimeoutMs = 2000;
    private int defaultMaxRetries = 0;
    private int defaultIdempotencyTtlSeconds = 1800;
    private boolean defaultToolEnabled = true;
    private boolean defaultIdempotencyEnabled = true;
    private Map<String, ToolPolicyProperties> tools = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public void setDefaultTimeoutMs(int defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public int getDefaultMaxRetries() {
        return defaultMaxRetries;
    }

    public void setDefaultMaxRetries(int defaultMaxRetries) {
        this.defaultMaxRetries = defaultMaxRetries;
    }

    public int getDefaultIdempotencyTtlSeconds() {
        return defaultIdempotencyTtlSeconds;
    }

    public void setDefaultIdempotencyTtlSeconds(int defaultIdempotencyTtlSeconds) {
        this.defaultIdempotencyTtlSeconds = defaultIdempotencyTtlSeconds;
    }

    public boolean isDefaultToolEnabled() {
        return defaultToolEnabled;
    }

    public void setDefaultToolEnabled(boolean defaultToolEnabled) {
        this.defaultToolEnabled = defaultToolEnabled;
    }

    public boolean isDefaultIdempotencyEnabled() {
        return defaultIdempotencyEnabled;
    }

    public void setDefaultIdempotencyEnabled(boolean defaultIdempotencyEnabled) {
        this.defaultIdempotencyEnabled = defaultIdempotencyEnabled;
    }

    public Map<String, ToolPolicyProperties> getTools() {
        return tools;
    }

    public void setTools(Map<String, ToolPolicyProperties> tools) {
        this.tools = tools;
    }

    public static class ToolPolicyProperties {

        private Boolean enabled;
        private Integer timeoutMs;
        private Integer maxRetries;
        private Boolean idempotencyEnabled;
        private Integer idempotencyTtlSeconds;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(Integer timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public Integer getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(Integer maxRetries) {
            this.maxRetries = maxRetries;
        }

        public Boolean getIdempotencyEnabled() {
            return idempotencyEnabled;
        }

        public void setIdempotencyEnabled(Boolean idempotencyEnabled) {
            this.idempotencyEnabled = idempotencyEnabled;
        }

        public Integer getIdempotencyTtlSeconds() {
            return idempotencyTtlSeconds;
        }

        public void setIdempotencyTtlSeconds(Integer idempotencyTtlSeconds) {
            this.idempotencyTtlSeconds = idempotencyTtlSeconds;
        }
    }
}
