package com.bones.gateway.service;

import com.bones.gateway.config.M6CacheProperties;
import com.bones.gateway.integration.ai.model.AiChatMessage;
import com.bones.gateway.integration.ai.model.AiChatRequest;
import com.bones.gateway.integration.ai.model.AiChatResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class HotResponseCacheService {

    private static final Logger log = LoggerFactory.getLogger(HotResponseCacheService.class);

    private final M6CacheProperties cacheProperties;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public HotResponseCacheService(M6CacheProperties cacheProperties,
                                   StringRedisTemplate stringRedisTemplate,
                                   ObjectMapper objectMapper,
                                   MeterRegistry meterRegistry) {
        this.cacheProperties = cacheProperties;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public AiChatResponse getIfPresent(AiChatRequest request) {
        if (!cacheProperties.isEnabled() || !cacheProperties.isHotResponseEnabled() || shouldBypass(request.metadata())) {
            return null;
        }

        String key = buildKey(request);
        String value = stringRedisTemplate.opsForValue().get(key);
        if (!StringUtils.hasText(value)) {
            meterRegistry.counter("jarvis.cache.hot.miss").increment();
            return null;
        }

        try {
            CachedAiResponse cached = objectMapper.readValue(value, CachedAiResponse.class);
            meterRegistry.counter("jarvis.cache.hot.hit").increment();
            return new AiChatResponse(cached.content(), cached.model(), cached.finishReason());
        } catch (Exception ex) {
            meterRegistry.counter("jarvis.cache.hot.deserialize_error").increment();
            log.warn("hot response cache deserialize failed, key={}", key, ex);
            return null;
        }
    }

    public void put(AiChatRequest request, AiChatResponse response) {
        if (!cacheProperties.isEnabled() || !cacheProperties.isHotResponseEnabled() || shouldBypass(request.metadata())) {
            return;
        }
        if (response == null || !StringUtils.hasText(response.content())) {
            return;
        }

        String key = buildKey(request);
        CachedAiResponse value = new CachedAiResponse(response.content(), response.model(), response.finishReason());
        try {
            stringRedisTemplate.opsForValue().set(
                    key,
                    objectMapper.writeValueAsString(value),
                    Duration.ofSeconds(cacheProperties.getHotResponseTtlSeconds())
            );
            meterRegistry.counter("jarvis.cache.hot.store").increment();
        } catch (Exception ex) {
            meterRegistry.counter("jarvis.cache.hot.write_error").increment();
            log.warn("hot response cache write failed, key={}", key, ex);
        }
    }

    private String buildKey(AiChatRequest request) {
        String payload = toStablePayload(request);
        return "jarvis:cache:hot:" + sha256Hex(payload);
    }

    private String toStablePayload(AiChatRequest request) {
        List<AiChatMessage> messages = request.messages() == null || request.messages().isEmpty()
                ? List.of(new AiChatMessage("user", request.message()))
                : request.messages();
        String provider = request.provider() == null ? "" : request.provider().trim().toLowerCase();
        String model = request.model() == null ? "" : request.model().trim();
        return provider + "|" + model + "|" + request.userId() + "|" + toJson(messages);
    }

    private String toJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException ex) {
            return String.valueOf(object);
        }
    }

    private String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encoded);
        } catch (Exception ex) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private boolean shouldBypass(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        return isTrue(metadata.get("cacheBypass")) || isTrue(metadata.get("noCache"));
    }

    private boolean isTrue(Object value) {
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        if (value instanceof String strValue) {
            return "true".equalsIgnoreCase(strValue);
        }
        return false;
    }

    private record CachedAiResponse(
            String content,
            String model,
            String finishReason
    ) {
    }
}
