package com.bones.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bones.gateway.config.BillingProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private BillingService billingService;

    @BeforeEach
    void setUp() {
        BillingProperties billingProperties = new BillingProperties();
        billingProperties.setEnabled(true);
        billingProperties.setDefaultInputCostPer1k(0.001);
        billingProperties.setDefaultOutputCostPer1k(0.002);

        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(stringRedisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        billingService = new BillingService(billingProperties, stringRedisTemplate);
    }

    @Test
    void recordUsage_shouldCalculateEstimatedCost() {
        BillingService.BillingUsage usage = billingService.recordUsage(1001L, "deepseek", "deepseek-chat", 1000, 500);

        assertEquals(1000, usage.promptTokens());
        assertEquals(500, usage.completionTokens());
        assertEquals(0.002, usage.estimatedCostUsd());
        assertEquals(BillingService.UsageSource.ESTIMATED, usage.usageSource());
    }

    @Test
    void recordUsage_shouldWriteActualSourceForWorkspaceLedger() {
        BillingService.BillingUsage usage = billingService.recordUsage(
                1001L,
                88L,
                "glm",
                "glm-4.6v-flashx",
                1200,
                300,
                1500,
                BillingService.UsageSource.ACTUAL
        );

        assertEquals(BillingService.UsageSource.ACTUAL, usage.usageSource());
        verify(hashOperations).increment(contains(":ws:"), eq("actualRequests"), eq(1L));
    }
}
