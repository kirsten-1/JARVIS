package com.bones.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.bones.gateway.dto.MetricsOverviewResponse;
import com.bones.gateway.dto.ProviderMetricsResponse;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

@ExtendWith(MockitoExtension.class)
class OpsMetricsServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private ZSetOperations<String, String> zSetOperations;
    @Mock
    private BillingService billingService;

    private OpsMetricsService opsMetricsService;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        opsMetricsService = new OpsMetricsService(stringRedisTemplate, billingService);
    }

    @Test
    void getOverview_shouldAggregateWorkspaceMetricsAndCostTrend() {
        Long workspaceId = 88L;
        LocalDate from = LocalDate.of(2026, 2, 28);
        LocalDate to = LocalDate.of(2026, 3, 1);

        String overviewDay1 = "jarvis:ops:chat:" + from + ":ws:" + workspaceId;
        String overviewDay2 = "jarvis:ops:chat:" + to + ":ws:" + workspaceId;
        when(hashOperations.entries(eq(overviewDay1))).thenReturn(Map.of("total", "10", "success", "8", "failed", "2"));
        when(hashOperations.entries(eq(overviewDay2))).thenReturn(Map.of("total", "20", "success", "18", "failed", "2"));

        String fallbackDay1 = "jarvis:ops:fallback:" + from + ":ws:" + workspaceId;
        String fallbackDay2 = "jarvis:ops:fallback:" + to + ":ws:" + workspaceId;
        when(hashOperations.entries(eq(fallbackDay1))).thenReturn(Map.of("hits", "1"));
        when(hashOperations.entries(eq(fallbackDay2))).thenReturn(Map.of("hits", "3"));

        String ttftDay1 = "jarvis:ops:ttft:" + from + ":ws:" + workspaceId;
        String ttftDay2 = "jarvis:ops:ttft:" + to + ":ws:" + workspaceId;
        when(zSetOperations.rangeWithScores(eq(ttftDay1), eq(0L), eq(-1L))).thenReturn(scored(100.0, 200.0));
        when(zSetOperations.rangeWithScores(eq(ttftDay2), eq(0L), eq(-1L))).thenReturn(scored(150.0, 300.0));

        when(billingService.aggregateDailyWorkspaceUsage(workspaceId, from))
                .thenReturn(new BillingService.WorkspaceDailyUsage(from, 3L, 300L, 0.003, 2L, 1L));
        when(billingService.aggregateDailyWorkspaceUsage(workspaceId, to))
                .thenReturn(new BillingService.WorkspaceDailyUsage(to, 5L, 500L, 0.005, 4L, 1L));

        MetricsOverviewResponse overview = opsMetricsService.getOverview(workspaceId, from, to);

        assertEquals(workspaceId, overview.workspaceId());
        assertEquals(30L, overview.totalRequests());
        assertEquals(26L, overview.successRequests());
        assertEquals(4L, overview.failedRequests());
        assertEquals(4L, overview.fallbackHits());
        assertEquals(26.0 / 30.0, overview.successRate());
        assertEquals(4.0 / 30.0, overview.fallbackRate());
        assertEquals(150.0, overview.ttftP50Ms());
        assertEquals(300.0, overview.ttftP90Ms());
        assertEquals(300.0, overview.ttftP99Ms());
        assertEquals(2, overview.costTrend().size());
        assertEquals(3L, overview.costTrend().get(0).requests());
        assertEquals(5L, overview.costTrend().get(1).requests());
    }

    @Test
    void getProviders_shouldAggregateAndSortByTotalRequests() {
        Long workspaceId = 88L;
        LocalDate from = LocalDate.of(2026, 2, 28);
        LocalDate to = LocalDate.of(2026, 3, 1);

        when(stringRedisTemplate.keys(eq("jarvis:ops:chat:" + from + ":ws:" + workspaceId + ":provider:*")))
                .thenReturn(Set.of(
                        "jarvis:ops:chat:" + from + ":ws:" + workspaceId + ":provider:deepseek",
                        "jarvis:ops:chat:" + from + ":ws:" + workspaceId + ":provider:glm"
                ));
        when(stringRedisTemplate.keys(eq("jarvis:ops:chat:" + to + ":ws:" + workspaceId + ":provider:*")))
                .thenReturn(Set.of(
                        "jarvis:ops:chat:" + to + ":ws:" + workspaceId + ":provider:deepseek",
                        "jarvis:ops:chat:" + to + ":ws:" + workspaceId + ":provider:glm"
                ));

        when(hashOperations.entries(eq("jarvis:ops:chat:" + from + ":ws:" + workspaceId + ":provider:deepseek")))
                .thenReturn(Map.of("total", "8", "success", "7", "failed", "1", "fallbackHits", "1"));
        when(hashOperations.entries(eq("jarvis:ops:chat:" + to + ":ws:" + workspaceId + ":provider:deepseek")))
                .thenReturn(Map.of("total", "10", "success", "9", "failed", "1", "fallbackHits", "0"));

        when(hashOperations.entries(eq("jarvis:ops:chat:" + from + ":ws:" + workspaceId + ":provider:glm")))
                .thenReturn(Map.of("total", "3", "success", "2", "failed", "1", "fallbackHits", "1"));
        when(hashOperations.entries(eq("jarvis:ops:chat:" + to + ":ws:" + workspaceId + ":provider:glm")))
                .thenReturn(Map.of("total", "2", "success", "1", "failed", "1", "fallbackHits", "1"));

        when(zSetOperations.rangeWithScores(eq("jarvis:ops:ttft:" + from + ":ws:" + workspaceId + ":provider:deepseek"), eq(0L), eq(-1L)))
                .thenReturn(scored(120.0, 180.0));
        when(zSetOperations.rangeWithScores(eq("jarvis:ops:ttft:" + to + ":ws:" + workspaceId + ":provider:deepseek"), eq(0L), eq(-1L)))
                .thenReturn(scored(140.0));

        when(zSetOperations.rangeWithScores(eq("jarvis:ops:ttft:" + from + ":ws:" + workspaceId + ":provider:glm"), eq(0L), eq(-1L)))
                .thenReturn(Set.<ZSetOperations.TypedTuple<String>>of());
        when(zSetOperations.rangeWithScores(eq("jarvis:ops:ttft:" + to + ":ws:" + workspaceId + ":provider:glm"), eq(0L), eq(-1L)))
                .thenReturn(Set.<ZSetOperations.TypedTuple<String>>of());

        var providers = opsMetricsService.getProviders(workspaceId, from, to);

        assertEquals(2, providers.size());
        ProviderMetricsResponse first = providers.get(0);
        ProviderMetricsResponse second = providers.get(1);

        assertEquals("deepseek", first.provider());
        assertEquals(18L, first.totalRequests());
        assertEquals(16L, first.successRequests());
        assertEquals(2L, first.failedRequests());
        assertEquals(1L, first.fallbackHits());
        assertEquals(140.0, first.ttftP50Ms());
        assertEquals(180.0, first.ttftP90Ms());
        assertEquals(180.0, first.ttftP99Ms());

        assertEquals("glm", second.provider());
        assertEquals(5L, second.totalRequests());
        assertEquals(3L, second.successRequests());
        assertEquals(2L, second.failedRequests());
        assertEquals(2L, second.fallbackHits());
        assertNull(second.ttftP50Ms());
        assertNull(second.ttftP90Ms());
        assertNull(second.ttftP99Ms());
    }

    private Set<ZSetOperations.TypedTuple<String>> scored(double... values) {
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        for (int i = 0; i < values.length; i++) {
            tuples.add(new DefaultTypedTuple<>("m" + i, values[i]));
        }
        return tuples;
    }
}
