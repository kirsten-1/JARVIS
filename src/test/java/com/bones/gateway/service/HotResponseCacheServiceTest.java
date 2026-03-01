package com.bones.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bones.gateway.config.M6CacheProperties;
import com.bones.gateway.integration.ai.model.AiChatMessage;
import com.bones.gateway.integration.ai.model.AiChatRequest;
import com.bones.gateway.integration.ai.model.AiChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class HotResponseCacheServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private HotResponseCacheService hotResponseCacheService;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        M6CacheProperties properties = new M6CacheProperties();
        properties.setEnabled(true);
        properties.setHotResponseEnabled(true);
        properties.setHotResponseTtlSeconds(30);
        hotResponseCacheService = new HotResponseCacheService(
                properties,
                stringRedisTemplate,
                new ObjectMapper(),
                new SimpleMeterRegistry()
        );
    }

    @Test
    void getIfPresent_shouldReturnCachedResponse() {
        when(valueOperations.get(anyString()))
                .thenReturn("{\"content\":\"cached answer\",\"model\":\"glm-4.6v-flashx\",\"finishReason\":\"stop\"}");

        AiChatRequest request = new AiChatRequest(
                "hello",
                1L,
                1001L,
                "glm",
                "glm-4.6v-flashx",
                List.of(new AiChatMessage("user", "hello")),
                Map.of()
        );

        AiChatResponse response = hotResponseCacheService.getIfPresent(request);

        assertNotNull(response);
        assertEquals("cached answer", response.content());
        assertEquals("glm-4.6v-flashx", response.model());
    }

    @Test
    void getIfPresent_shouldBypassWhenMetadataNoCache() {
        AiChatRequest request = new AiChatRequest(
                "hello",
                1L,
                1001L,
                "glm",
                "glm-4.6v-flashx",
                List.of(new AiChatMessage("user", "hello")),
                Map.of("noCache", true)
        );

        AiChatResponse response = hotResponseCacheService.getIfPresent(request);

        assertNull(response);
        verify(valueOperations, never()).get(anyString());
    }

    @Test
    void put_shouldStoreResponseWithTtl() {
        AiChatRequest request = new AiChatRequest(
                "hello",
                1L,
                1001L,
                "glm",
                "glm-4.6v-flashx",
                List.of(new AiChatMessage("user", "hello")),
                Map.of()
        );
        AiChatResponse response = new AiChatResponse("world", "glm-4.6v-flashx", "stop");

        hotResponseCacheService.put(request, response);

        verify(valueOperations).set(anyString(), anyString(), any(Duration.class));
    }
}
