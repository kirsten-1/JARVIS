package com.bones.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.bones.gateway.config.StreamSessionProperties;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class StreamSessionServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private StreamSessionService streamSessionService;

    @BeforeEach
    void setUp() {
        StreamSessionProperties properties = new StreamSessionProperties();
        properties.setEnabled(true);
        properties.setTtlSeconds(3600);

        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        streamSessionService = new StreamSessionService(properties, stringRedisTemplate);
    }

    @Test
    void snapshot_shouldReturnSessionData() {
        String streamId = streamSessionService.createSession(1L, 1001L, 2001L);
        assertNotNull(streamId);

        Map<Object, Object> meta = new HashMap<>();
        meta.put("conversationId", "1");
        meta.put("userId", "1001");
        meta.put("assistantMessageId", "2001");
        meta.put("status", "streaming");
        meta.put("updatedAt", "2026-02-28T10:00:00Z");

        when(hashOperations.entries(anyString())).thenReturn(meta);
        when(valueOperations.get(anyString())).thenReturn("hello");

        StreamSessionService.StreamSessionSnapshot snapshot = streamSessionService.getSnapshot(streamId);

        assertEquals("streaming", snapshot.status());
        assertEquals("hello", snapshot.content());
        assertEquals(1L, snapshot.conversationId());
    }
}
