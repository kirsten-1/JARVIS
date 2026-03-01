package com.bones.gateway.service;

import com.bones.gateway.common.BusinessException;
import com.bones.gateway.common.ErrorCode;
import com.bones.gateway.config.GuardProperties;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RequestGuardService {

    private final GuardProperties guardProperties;
    private final StringRedisTemplate stringRedisTemplate;

    public RequestGuardService(GuardProperties guardProperties,
                               StringRedisTemplate stringRedisTemplate) {
        this.guardProperties = guardProperties;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void checkAndConsume(Long userId) {
        checkAndConsume(userId, null, null);
    }

    public void checkAndConsume(Long userId, String provider, String model) {
        if (!guardProperties.isEnabled() || userId == null) {
            return;
        }

        checkPerMinute(userId);
        checkPerDay(userId);
        checkPerDayProviderModel(userId, provider, model);
    }

    private void checkPerMinute(Long userId) {
        long minuteBucket = System.currentTimeMillis() / 60000;
        String key = "jarvis:guard:rate:" + userId + ":" + minuteBucket;
        Long count = stringRedisTemplate.opsForValue().increment(key);

        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, Duration.ofMinutes(2));
        }

        if (count != null && count > guardProperties.getPerMinuteLimit()) {
            throw new BusinessException(
                    ErrorCode.RATE_LIMITED,
                    ErrorCode.RATE_LIMITED.getMessage(),
                    HttpStatus.TOO_MANY_REQUESTS
            );
        }
    }

    private void checkPerDay(Long userId) {
        LocalDate today = LocalDate.now();
        String key = "jarvis:guard:quota:" + userId + ":" + today;
        Long count = stringRedisTemplate.opsForValue().increment(key);

        if (count != null && count == 1L) {
            Duration ttl = Duration.between(
                    LocalDateTime.now(),
                    LocalDateTime.of(today.plusDays(1), LocalTime.MIDNIGHT)
            ).plusHours(1);
            if (!ttl.isNegative() && !ttl.isZero()) {
                stringRedisTemplate.expire(key, ttl);
            }
        }

        if (count != null && count > guardProperties.getPerDayQuota()) {
            throw new BusinessException(
                    ErrorCode.QUOTA_EXCEEDED,
                    ErrorCode.QUOTA_EXCEEDED.getMessage(),
                    HttpStatus.TOO_MANY_REQUESTS
            );
        }
    }

    private void checkPerDayProviderModel(Long userId, String provider, String model) {
        String safeProvider = normalize(provider, "unknown");
        String safeModel = normalize(model, "default");

        LocalDate today = LocalDate.now();
        String key = "jarvis:guard:pmquota:" + userId + ":" + today + ":" + safeProvider + ":" + safeModel;
        Long count = stringRedisTemplate.opsForValue().increment(key);

        if (count != null && count == 1L) {
            Duration ttl = Duration.between(
                    LocalDateTime.now(),
                    LocalDateTime.of(today.plusDays(1), LocalTime.MIDNIGHT)
            ).plusHours(1);
            if (!ttl.isNegative() && !ttl.isZero()) {
                stringRedisTemplate.expire(key, ttl);
            }
        }

        if (count != null && count > guardProperties.getPerDayProviderModelQuota()) {
            throw new BusinessException(
                    ErrorCode.QUOTA_EXCEEDED,
                    "provider/model quota exceeded",
                    HttpStatus.TOO_MANY_REQUESTS
            );
        }
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toLowerCase();
    }
}
