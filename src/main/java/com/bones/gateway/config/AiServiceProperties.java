package com.bones.gateway.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "jarvis.ai")
public class AiServiceProperties {

    @NotBlank
    private String defaultProvider = "local";

    private boolean fallbackEnabled = false;

    private String fallbackProvider;

    @Min(100)
    private int connectTimeoutMs = 3000;

    @Min(100)
    private int readTimeoutMs = 30000;

    private boolean retryEnabled = false;

    @Min(0)
    private int maxRetries = 0;

    @Min(50)
    private int retryBackoffMs = 300;

    // Legacy single-provider fields kept for backward compatibility
    @NotBlank
    private String baseUrl = "http://localhost:8000";

    @NotBlank
    private String chatPath = "/v1/chat";

    @NotBlank
    private String streamPath = "/v1/chat/stream";

    private String apiKey;

    private String model;

    private Map<String, ProviderProperties> providers = new LinkedHashMap<>();

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public boolean isFallbackEnabled() {
        return fallbackEnabled;
    }

    public void setFallbackEnabled(boolean fallbackEnabled) {
        this.fallbackEnabled = fallbackEnabled;
    }

    public String getFallbackProvider() {
        return fallbackProvider;
    }

    public void setFallbackProvider(String fallbackProvider) {
        this.fallbackProvider = fallbackProvider;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public boolean isRetryEnabled() {
        return retryEnabled;
    }

    public void setRetryEnabled(boolean retryEnabled) {
        this.retryEnabled = retryEnabled;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(int retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getChatPath() {
        return chatPath;
    }

    public void setChatPath(String chatPath) {
        this.chatPath = chatPath;
    }

    public String getStreamPath() {
        return streamPath;
    }

    public void setStreamPath(String streamPath) {
        this.streamPath = streamPath;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Map<String, ProviderProperties> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderProperties> providers) {
        this.providers = providers;
    }

    public ProviderProperties toLegacyProvider() {
        ProviderProperties provider = new ProviderProperties();
        provider.setEnabled(true);
        provider.setProtocol(ProviderProtocol.OPENAI_COMPATIBLE);
        provider.setBaseUrl(baseUrl);
        provider.setChatPath(chatPath);
        provider.setStreamPath(streamPath);
        provider.setApiKey(apiKey);
        provider.setModel(model);
        return provider;
    }

    public enum ProviderProtocol {
        OPENAI_COMPATIBLE,
        GEMINI
    }

    public static class ProviderProperties {

        private boolean enabled = true;

        private ProviderProtocol protocol = ProviderProtocol.OPENAI_COMPATIBLE;

        private String baseUrl;

        private String chatPath = "/v1/chat/completions";

        private String streamPath = "/v1/chat/completions";

        private String apiKey;

        private String model;

        private String apiKeyHeader = "Authorization";

        private String apiKeyPrefix = "Bearer ";

        private boolean apiKeyInQuery = false;

        private String apiKeyQueryName = "key";

        private Map<String, String> headers = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public ProviderProtocol getProtocol() {
            return protocol;
        }

        public void setProtocol(ProviderProtocol protocol) {
            this.protocol = protocol;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getChatPath() {
            return chatPath;
        }

        public void setChatPath(String chatPath) {
            this.chatPath = chatPath;
        }

        public String getStreamPath() {
            return streamPath;
        }

        public void setStreamPath(String streamPath) {
            this.streamPath = streamPath;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKeyHeader() {
            return apiKeyHeader;
        }

        public void setApiKeyHeader(String apiKeyHeader) {
            this.apiKeyHeader = apiKeyHeader;
        }

        public String getApiKeyPrefix() {
            return apiKeyPrefix;
        }

        public void setApiKeyPrefix(String apiKeyPrefix) {
            this.apiKeyPrefix = apiKeyPrefix;
        }

        public boolean isApiKeyInQuery() {
            return apiKeyInQuery;
        }

        public void setApiKeyInQuery(boolean apiKeyInQuery) {
            this.apiKeyInQuery = apiKeyInQuery;
        }

        public String getApiKeyQueryName() {
            return apiKeyQueryName;
        }

        public void setApiKeyQueryName(String apiKeyQueryName) {
            this.apiKeyQueryName = apiKeyQueryName;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }
    }
}
