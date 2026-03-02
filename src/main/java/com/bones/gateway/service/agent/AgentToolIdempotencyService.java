package com.bones.gateway.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class AgentToolIdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(AgentToolIdempotencyService.class);
    private static final String KEY_PREFIX = "jarvis:agent:tool:idemp:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public AgentToolIdempotencyService(StringRedisTemplate stringRedisTemplate,
                                       ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<CachedToolResult> find(Lookup lookup) {
        if (!hasText(lookup.idempotencyKey())) {
            return Optional.empty();
        }
        try {
            String value = stringRedisTemplate.opsForValue().get(redisKey(lookup));
            if (!hasText(value)) {
                return Optional.empty();
            }
            return Optional.ofNullable(objectMapper.readValue(value, CachedToolResult.class));
        } catch (Exception ex) {
            log.warn("agent tool idempotency read failed", ex);
            return Optional.empty();
        }
    }

    public void save(Lookup lookup, CachedToolResult result, int ttlSeconds) {
        if (!hasText(lookup.idempotencyKey()) || result == null) {
            return;
        }
        int safeTtl = Math.max(60, ttlSeconds);
        try {
            String json = objectMapper.writeValueAsString(result);
            stringRedisTemplate.opsForValue().set(redisKey(lookup), json, Duration.ofSeconds(safeTtl));
        } catch (Exception ex) {
            log.warn("agent tool idempotency write failed", ex);
        }
    }

    private String redisKey(Lookup lookup) {
        String raw = String.join("|",
                normalize(lookup.idempotencyKey()),
                normalize(lookup.userId()),
                normalize(lookup.workspaceId()),
                normalize(lookup.conversationId()),
                normalize(lookup.toolName()),
                normalizeInput(lookup.toolInput())
        );
        return KEY_PREFIX + sha256Hex(raw);
    }

    private String normalize(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim();
    }

    private String normalizeInput(String toolInput) {
        if (!hasText(toolInput)) {
            return "{}";
        }
        return toolInput.replaceAll("\\s+", " ").trim();
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception ex) {
            return Integer.toHexString(Objects.hashCode(input));
        }
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    public record Lookup(
            String idempotencyKey,
            Long userId,
            Long workspaceId,
            Long conversationId,
            String toolName,
            String toolInput
    ) {
    }

    public record CachedToolResult(
            String status,
            String input,
            String output,
            String toolCallId
    ) {
    }
}
