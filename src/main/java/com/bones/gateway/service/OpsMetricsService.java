package com.bones.gateway.service;

import com.bones.gateway.dto.DailyCostPointResponse;
import com.bones.gateway.dto.MetricsOverviewResponse;
import com.bones.gateway.dto.ProviderMetricsResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

@Service
public class OpsMetricsService {

    private static final Duration METRIC_TTL = Duration.ofDays(8);
    private static final String MODE_SYNC = "sync";
    private static final String MODE_STREAM = "stream";

    private final StringRedisTemplate stringRedisTemplate;
    private final BillingService billingService;

    public OpsMetricsService(StringRedisTemplate stringRedisTemplate,
                             BillingService billingService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.billingService = billingService;
    }

    public void recordSyncResult(Long workspaceId, String provider, boolean success) {
        recordChatResult(workspaceId, provider, MODE_SYNC, success);
    }

    public void recordStreamResult(Long workspaceId, String provider, boolean success) {
        recordChatResult(workspaceId, provider, MODE_STREAM, success);
    }

    public void recordStreamFallback(Long workspaceId, String provider) {
        if (workspaceId == null) {
            return;
        }
        String safeProvider = normalize(provider, "default");
        LocalDate today = LocalDate.now();
        String fallbackKey = fallbackWorkspaceKey(today, workspaceId);
        String providerKey = providerKey(today, workspaceId, safeProvider);
        try {
            stringRedisTemplate.opsForHash().increment(fallbackKey, "hits", 1);
            stringRedisTemplate.opsForHash().increment(providerKey, "fallbackHits", 1);
            stringRedisTemplate.expire(fallbackKey, METRIC_TTL);
            stringRedisTemplate.expire(providerKey, METRIC_TTL);
        } catch (Exception ignored) {
            // metrics must not impact main flow
        }
    }

    public void recordProviderFallbackGlobal(String fromProvider, String toProvider, String mode) {
        LocalDate today = LocalDate.now();
        String key = "jarvis:ops:fallback:global:" + today + ":" + normalize(fromProvider, "unknown") + ":" + normalize(mode, MODE_SYNC);
        try {
            stringRedisTemplate.opsForHash().increment(key, "hits", 1);
            stringRedisTemplate.opsForHash().increment(key, "to." + normalize(toProvider, "unknown"), 1);
            stringRedisTemplate.expire(key, METRIC_TTL);
        } catch (Exception ignored) {
            // metrics must not impact main flow
        }
    }

    public void recordTtft(Long workspaceId, String provider, long ttftMs) {
        if (workspaceId == null) {
            return;
        }
        LocalDate today = LocalDate.now();
        String safeProvider = normalize(provider, "default");
        String member = System.currentTimeMillis() + ":" + UUID.randomUUID();
        try {
            stringRedisTemplate.opsForZSet().add(ttftKey(today, workspaceId), member, ttftMs);
            stringRedisTemplate.opsForZSet().add(ttftProviderKey(today, workspaceId, safeProvider), member, ttftMs);
            stringRedisTemplate.expire(ttftKey(today, workspaceId), METRIC_TTL);
            stringRedisTemplate.expire(ttftProviderKey(today, workspaceId, safeProvider), METRIC_TTL);
        } catch (Exception ignored) {
            // metrics must not impact main flow
        }
    }

    public MetricsOverviewResponse getOverview(Long workspaceId, LocalDate from, LocalDate to) {
        DateRange range = normalizeRange(from, to);
        long total = 0L;
        long success = 0L;
        long failed = 0L;
        long fallbackHits = 0L;
        List<Double> ttftSamples = new ArrayList<>();
        List<DailyCostPointResponse> costTrend = new ArrayList<>();

        for (LocalDate date : days(range.from(), range.to())) {
            Map<Object, Object> overview = stringRedisTemplate.opsForHash().entries(overviewKey(date, workspaceId));
            total += parseLong(overview.get("total"));
            success += parseLong(overview.get("success"));
            failed += parseLong(overview.get("failed"));

            Map<Object, Object> fallback = stringRedisTemplate.opsForHash().entries(fallbackWorkspaceKey(date, workspaceId));
            fallbackHits += parseLong(fallback.get("hits"));

            ttftSamples.addAll(loadTtftSamples(ttftKey(date, workspaceId)));

            BillingService.WorkspaceDailyUsage usage = billingService.aggregateDailyWorkspaceUsage(workspaceId, date);
            costTrend.add(new DailyCostPointResponse(
                    usage.date(),
                    usage.requests(),
                    usage.totalTokens(),
                    usage.costUsd(),
                    usage.actualRequests(),
                    usage.estimatedRequests()
            ));
        }

        return new MetricsOverviewResponse(
                workspaceId,
                range.from(),
                range.to(),
                total,
                success,
                failed,
                ratio(success, total),
                fallbackHits,
                ratio(fallbackHits, total),
                percentile(ttftSamples, 0.5),
                percentile(ttftSamples, 0.9),
                percentile(ttftSamples, 0.99),
                costTrend
        );
    }

    public List<ProviderMetricsResponse> getProviders(Long workspaceId, LocalDate from, LocalDate to) {
        DateRange range = normalizeRange(from, to);
        Set<String> providers = collectProviders(workspaceId, range.from(), range.to());
        List<ProviderMetricsResponse> result = new ArrayList<>();

        for (String provider : providers) {
            long total = 0L;
            long success = 0L;
            long failed = 0L;
            long fallbackHits = 0L;
            List<Double> ttftSamples = new ArrayList<>();

            for (LocalDate date : days(range.from(), range.to())) {
                Map<Object, Object> entries = stringRedisTemplate.opsForHash()
                        .entries(providerKey(date, workspaceId, provider));
                total += parseLong(entries.get("total"));
                success += parseLong(entries.get("success"));
                failed += parseLong(entries.get("failed"));
                fallbackHits += parseLong(entries.get("fallbackHits"));
                ttftSamples.addAll(loadTtftSamples(ttftProviderKey(date, workspaceId, provider)));
            }

            result.add(new ProviderMetricsResponse(
                    provider,
                    total,
                    success,
                    failed,
                    ratio(success, total),
                    fallbackHits,
                    ratio(fallbackHits, total),
                    percentile(ttftSamples, 0.5),
                    percentile(ttftSamples, 0.9),
                    percentile(ttftSamples, 0.99)
            ));
        }

        result.sort((a, b) -> Long.compare(b.totalRequests(), a.totalRequests()));
        return result;
    }

    private void recordChatResult(Long workspaceId, String provider, String mode, boolean success) {
        if (workspaceId == null) {
            return;
        }
        String safeProvider = normalize(provider, "default");
        String safeMode = normalize(mode, MODE_SYNC);
        LocalDate today = LocalDate.now();

        String overviewKey = overviewKey(today, workspaceId);
        String providerKey = providerKey(today, workspaceId, safeProvider);
        String modeTotalField = safeMode + "Total";
        String modeResultField = safeMode + (success ? "Success" : "Failed");
        try {
            stringRedisTemplate.opsForHash().increment(overviewKey, "total", 1);
            stringRedisTemplate.opsForHash().increment(overviewKey, success ? "success" : "failed", 1);
            stringRedisTemplate.opsForHash().increment(overviewKey, modeTotalField, 1);
            stringRedisTemplate.opsForHash().increment(overviewKey, modeResultField, 1);

            stringRedisTemplate.opsForHash().increment(providerKey, "total", 1);
            stringRedisTemplate.opsForHash().increment(providerKey, success ? "success" : "failed", 1);
            stringRedisTemplate.opsForHash().increment(providerKey, modeTotalField, 1);
            stringRedisTemplate.opsForHash().increment(providerKey, modeResultField, 1);

            stringRedisTemplate.expire(overviewKey, METRIC_TTL);
            stringRedisTemplate.expire(providerKey, METRIC_TTL);
        } catch (Exception ignored) {
            // metrics must not impact main flow
        }
    }

    private Set<String> collectProviders(Long workspaceId, LocalDate from, LocalDate to) {
        Set<String> providers = new HashSet<>();
        for (LocalDate date : days(from, to)) {
            String pattern = "jarvis:ops:chat:" + date + ":ws:" + workspaceId + ":provider:*";
            Set<String> keys = stringRedisTemplate.keys(pattern);
            if (keys == null || keys.isEmpty()) {
                continue;
            }
            for (String key : keys) {
                int idx = key.lastIndexOf(":provider:");
                if (idx < 0) {
                    continue;
                }
                providers.add(key.substring(idx + ":provider:".length()));
            }
        }
        return providers;
    }

    private List<Double> loadTtftSamples(String key) {
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet().rangeWithScores(key, 0, -1);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        List<Double> samples = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getScore() != null) {
                samples.add(tuple.getScore());
            }
        }
        return samples;
    }

    private String overviewKey(LocalDate date, Long workspaceId) {
        return "jarvis:ops:chat:" + date + ":ws:" + workspaceId;
    }

    private String providerKey(LocalDate date, Long workspaceId, String provider) {
        return overviewKey(date, workspaceId) + ":provider:" + provider;
    }

    private String fallbackWorkspaceKey(LocalDate date, Long workspaceId) {
        return "jarvis:ops:fallback:" + date + ":ws:" + workspaceId;
    }

    private String ttftKey(LocalDate date, Long workspaceId) {
        return "jarvis:ops:ttft:" + date + ":ws:" + workspaceId;
    }

    private String ttftProviderKey(LocalDate date, Long workspaceId, String provider) {
        return ttftKey(date, workspaceId) + ":provider:" + provider;
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toLowerCase(Locale.ROOT);
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

    private Double percentile(List<Double> samples, double pct) {
        if (samples == null || samples.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(pct * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    private double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return (double) numerator / denominator;
    }

    private List<LocalDate> days(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            days.add(cursor);
            cursor = cursor.plusDays(1);
        }
        return days;
    }

    private DateRange normalizeRange(LocalDate from, LocalDate to) {
        LocalDate end = Objects.requireNonNullElse(to, LocalDate.now());
        LocalDate start = Objects.requireNonNullElse(from, end.minusDays(6));
        if (start.isAfter(end)) {
            return new DateRange(end, end);
        }
        return new DateRange(start, end);
    }

    private record DateRange(LocalDate from, LocalDate to) {
    }
}
