package com.bones.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.bones.gateway.common.BusinessException;
import com.bones.gateway.common.ErrorCode;
import com.bones.gateway.config.GuardProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RequestGuardServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private RequestGuardService requestGuardService;

    @BeforeEach
    void setUp() {
        GuardProperties properties = new GuardProperties();
        properties.setEnabled(true);
        properties.setPerMinuteLimit(2);
        properties.setPerDayQuota(5);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        requestGuardService = new RequestGuardService(properties, stringRedisTemplate);
    }

    @Test
    void checkAndConsume_shouldThrowWhenRateLimited() {
        when(valueOperations.increment(anyString())).thenReturn(3L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> requestGuardService.checkAndConsume(1001L));

        assertEquals(ErrorCode.RATE_LIMITED, ex.getErrorCode());
    }

    @Test
    void checkAndConsume_shouldThrowWhenQuotaExceeded() {
        when(stringRedisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);
        when(valueOperations.increment(anyString())).thenReturn(1L).thenReturn(6L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> requestGuardService.checkAndConsume(1001L));

        assertEquals(ErrorCode.QUOTA_EXCEEDED, ex.getErrorCode());
    }
}
