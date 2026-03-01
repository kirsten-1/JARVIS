package com.bones.gateway.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "jarvis.cache")
public class M6CacheProperties {

    private boolean enabled = true;

    @Min(10)
    private int conversationListTtlSeconds = 120;

    @Min(10)
    private int messageListTtlSeconds = 60;

    private boolean hotResponseEnabled = true;

    @Min(5)
    private int hotResponseTtlSeconds = 45;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getConversationListTtlSeconds() {
        return conversationListTtlSeconds;
    }

    public void setConversationListTtlSeconds(int conversationListTtlSeconds) {
        this.conversationListTtlSeconds = conversationListTtlSeconds;
    }

    public int getMessageListTtlSeconds() {
        return messageListTtlSeconds;
    }

    public void setMessageListTtlSeconds(int messageListTtlSeconds) {
        this.messageListTtlSeconds = messageListTtlSeconds;
    }

    public boolean isHotResponseEnabled() {
        return hotResponseEnabled;
    }

    public void setHotResponseEnabled(boolean hotResponseEnabled) {
        this.hotResponseEnabled = hotResponseEnabled;
    }

    public int getHotResponseTtlSeconds() {
        return hotResponseTtlSeconds;
    }

    public void setHotResponseTtlSeconds(int hotResponseTtlSeconds) {
        this.hotResponseTtlSeconds = hotResponseTtlSeconds;
    }
}
