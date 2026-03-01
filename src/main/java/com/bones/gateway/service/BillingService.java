package com.bones.gateway.service;

import com.bones.gateway.config.BillingProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class BillingService {

    public enum UsageSource {
        ACTUAL,
        ESTIMATED
    }

    private final BillingProperties billingProperties;
    private final StringRedisTemplate stringRedisTemplate;

    public BillingService(BillingProperties billingProperties,
                          StringRedisTemplate stringRedisTemplate) {
        this.billingProperties = billingProperties;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public BillingUsage recordUsage(Long userId,
                                    String provider,
                                    String model,
                                    int promptTokens,
                                    int completionTokens) {
        return recordUsage(
                userId,
                null,
                provider,
                model,
                promptTokens,
                completionTokens,
                null,
                UsageSource.ESTIMATED
        );
    }

    public BillingUsage recordUsage(Long userId,
                                    Long workspaceId,
                                    String provider,
                                    String model,
                                    int promptTokens,
                                    int completionTokens,
                                    Integer totalTokens,
                                    UsageSource usageSource) {
        UsageSource safeUsageSource = usageSource == null ? UsageSource.ESTIMATED : usageSource;
        if (!billingProperties.isEnabled() || userId == null) {
            return new BillingUsage(promptTokens, completionTokens, 0.0, safeUsageSource);
        }

        String safeProvider = normalize(provider, "unknown");
        String safeModel = normalize(model, "default");
        int safeTotalTokens = totalTokens == null ? promptTokens + completionTokens : totalTokens;

        BillingProperties.Pricing pricing = billingProperties.resolve(safeProvider, safeModel);
        double inputCost = pricing.inputCostPer1k() * promptTokens / 1000.0;
        double outputCost = pricing.outputCostPer1k() * completionTokens / 1000.0;
        double totalCost = round6(inputCost + outputCost);

        String key = billingKey(LocalDate.now(), userId, safeProvider, safeModel);
        writeUsageEntry(key, promptTokens, completionTokens, safeTotalTokens, totalCost, safeUsageSource);

        if (workspaceId != null) {
            String workspaceKey = billingWorkspaceKey(LocalDate.now(), workspaceId, safeProvider, safeModel);
            writeUsageEntry(workspaceKey, promptTokens, completionTokens, safeTotalTokens, totalCost, safeUsageSource);
        }

        return new BillingUsage(promptTokens, completionTokens, totalCost, safeUsageSource);
    }

    public Map<Object, Object> getDailyUsage(Long userId, String provider, String model, LocalDate date) {
        String safeProvider = normalize(provider, "unknown");
        String safeModel = normalize(model, "default");
        String key = billingKey(date == null ? LocalDate.now() : date, userId, safeProvider, safeModel);
        return stringRedisTemplate.opsForHash().entries(key);
    }

    public Map<Object, Object> getDailyWorkspaceUsage(Long workspaceId, String provider, String model, LocalDate date) {
        String safeProvider = normalize(provider, "unknown");
        String safeModel = normalize(model, "default");
        String key = billingWorkspaceKey(date == null ? LocalDate.now() : date, workspaceId, safeProvider, safeModel);
        return stringRedisTemplate.opsForHash().entries(key);
    }

    public WorkspaceDailyUsage aggregateDailyWorkspaceUsage(Long workspaceId, LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        String pattern = "jarvis:billing:ws:" + day + ":" + workspaceId + ":*";
        Set<String> keys = stringRedisTemplate.keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return new WorkspaceDailyUsage(day, 0L, 0L, 0.0, 0L, 0L);
        }

        long requests = 0L;
        long totalTokens = 0L;
        long costMicros = 0L;
        long actualRequests = 0L;
        long estimatedRequests = 0L;

        for (String key : new HashSet<>(keys)) {
            Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
            requests += parseLong(entries.get("requests"));
            totalTokens += parseLong(entries.get("totalTokens"));
            costMicros += parseLong(entries.get("costMicros"));
            actualRequests += parseLong(entries.get("actualRequests"));
            estimatedRequests += parseLong(entries.get("estimatedRequests"));
        }

        return new WorkspaceDailyUsage(
                day,
                requests,
                totalTokens,
                costMicros / 1_000_000.0,
                actualRequests,
                estimatedRequests
        );
    }

    private String billingKey(LocalDate date, Long userId, String provider, String model) {
        return "jarvis:billing:" + date + ":" + userId + ":" + provider + ":" + model;
    }

    private String billingWorkspaceKey(LocalDate date, Long workspaceId, String provider, String model) {
        return "jarvis:billing:ws:" + date + ":" + workspaceId + ":" + provider + ":" + model;
    }

    private void writeUsageEntry(String key,
                                 int promptTokens,
                                 int completionTokens,
                                 int totalTokens,
                                 double totalCost,
                                 UsageSource usageSource) {
        String prefix = usageSource == UsageSource.ACTUAL ? "actual" : "estimated";

        stringRedisTemplate.opsForHash().increment(key, "requests", 1);
        stringRedisTemplate.opsForHash().increment(key, "promptTokens", promptTokens);
        stringRedisTemplate.opsForHash().increment(key, "completionTokens", completionTokens);
        stringRedisTemplate.opsForHash().increment(key, "totalTokens", totalTokens);
        stringRedisTemplate.opsForHash().increment(key, "costMicros", (long) Math.round(totalCost * 1_000_000));

        stringRedisTemplate.opsForHash().increment(key, prefix + "Requests", 1);
        stringRedisTemplate.opsForHash().increment(key, prefix + "PromptTokens", promptTokens);
        stringRedisTemplate.opsForHash().increment(key, prefix + "CompletionTokens", completionTokens);
        stringRedisTemplate.opsForHash().increment(key, prefix + "TotalTokens", totalTokens);
        stringRedisTemplate.opsForHash().increment(key, prefix + "CostMicros", (long) Math.round(totalCost * 1_000_000));

        stringRedisTemplate.opsForHash().put(key, "lastUsageSource", usageSource.name());
        stringRedisTemplate.expire(key, Duration.ofDays(8));
    }

    private double round6(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    private long parseLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toLowerCase();
    }

    public record BillingUsage(int promptTokens,
                               int completionTokens,
                               double estimatedCostUsd,
                               UsageSource usageSource) {

        public BillingUsage(int promptTokens, int completionTokens, double estimatedCostUsd) {
            this(promptTokens, completionTokens, estimatedCostUsd, UsageSource.ESTIMATED);
        }
    }

    public record WorkspaceDailyUsage(
            LocalDate date,
            long requests,
            long totalTokens,
            double costUsd,
            long actualRequests,
            long estimatedRequests
    ) {
    }
}
