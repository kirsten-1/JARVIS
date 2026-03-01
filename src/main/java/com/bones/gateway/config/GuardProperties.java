package com.bones.gateway.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "jarvis.guard")
public class GuardProperties {

    private boolean enabled = true;

    @Min(1)
    private int perMinuteLimit = 60;

    @Min(1)
    private int perDayQuota = 2000;

    @Min(1)
    private int perDayProviderModelQuota = 500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPerMinuteLimit() {
        return perMinuteLimit;
    }

    public void setPerMinuteLimit(int perMinuteLimit) {
        this.perMinuteLimit = perMinuteLimit;
    }

    public int getPerDayQuota() {
        return perDayQuota;
    }

    public void setPerDayQuota(int perDayQuota) {
        this.perDayQuota = perDayQuota;
    }

    public int getPerDayProviderModelQuota() {
        return perDayProviderModelQuota;
    }

    public void setPerDayProviderModelQuota(int perDayProviderModelQuota) {
        this.perDayProviderModelQuota = perDayProviderModelQuota;
    }
}
