package com.bones.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bones.gateway.config.M6CacheProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class ReadCacheServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private ReadCacheService readCacheService;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        M6CacheProperties properties = new M6CacheProperties();
        properties.setEnabled(true);
        readCacheService = new ReadCacheService(
                properties,
                stringRedisTemplate,
                new ObjectMapper(),
                new SimpleMeterRegistry()
        );
    }

    @Test
    void get_shouldReturnCachedValueWhenHit() {
        when(valueOperations.get("jarvis:test:key")).thenReturn("[\"A\",\"B\"]");
        AtomicBoolean loaderCalled = new AtomicBoolean(false);

        List<String> value = readCacheService.get(
                "jarvis:test:key",
                Duration.ofSeconds(30),
                new TypeReference<List<String>>() {
                },
                () -> {
                    loaderCalled.set(true);
                    return List.of("X");
                },
                "test"
        );

        assertEquals(List.of("A", "B"), value);
        assertFalse(loaderCalled.get());
    }

    @Test
    void get_shouldLoadAndWriteWhenMiss() {
        when(valueOperations.get("jarvis:test:miss")).thenReturn(null);
        AtomicInteger loaderCounter = new AtomicInteger(0);

        List<String> value = readCacheService.get(
                "jarvis:test:miss",
                Duration.ofSeconds(30),
                new TypeReference<List<String>>() {
                },
                () -> {
                    loaderCounter.incrementAndGet();
                    return List.of("DB");
                },
                "test"
        );

        assertEquals(List.of("DB"), value);
        assertEquals(1, loaderCounter.get());
        verify(valueOperations).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void get_shouldBypassWhenCacheDisabled() {
        M6CacheProperties properties = new M6CacheProperties();
        properties.setEnabled(false);
        ReadCacheService disabledService = new ReadCacheService(
                properties,
                stringRedisTemplate,
                new ObjectMapper(),
                new SimpleMeterRegistry()
        );
        AtomicBoolean loaderCalled = new AtomicBoolean(false);

        List<String> value = disabledService.get(
                "jarvis:test:disabled",
                Duration.ofSeconds(30),
                new TypeReference<List<String>>() {
                },
                () -> {
                    loaderCalled.set(true);
                    return List.of("DB");
                },
                "test"
        );

        assertTrue(loaderCalled.get());
        assertEquals(List.of("DB"), value);
        verify(valueOperations, never()).get(anyString());
    }
}
