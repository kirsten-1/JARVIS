package com.bones.gateway.service;

import com.bones.gateway.config.StreamSessionProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class StreamSessionService {

    private static final String STATUS_STREAMING = "streaming";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";

    private final StreamSessionProperties streamSessionProperties;
    private final StringRedisTemplate stringRedisTemplate;

    public StreamSessionService(StreamSessionProperties streamSessionProperties,
                                StringRedisTemplate stringRedisTemplate) {
        this.streamSessionProperties = streamSessionProperties;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public String createSession(Long conversationId, Long userId, Long assistantMessageId) {
        if (!streamSessionProperties.isEnabled()) {
            return null;
        }

        String streamId = UUID.randomUUID().toString().replace("-", "");
        String metaKey = metaKey(streamId);
        stringRedisTemplate.opsForHash().putAll(metaKey, Map.of(
                "conversationId", String.valueOf(conversationId),
                "userId", String.valueOf(userId),
                "assistantMessageId", String.valueOf(assistantMessageId),
                "status", STATUS_STREAMING,
                "createdAt", Instant.now().toString(),
                "updatedAt", Instant.now().toString()
        ));
        stringRedisTemplate.opsForValue().set(contentKey(streamId), "");

        Duration ttl = Duration.ofSeconds(streamSessionProperties.getTtlSeconds());
        stringRedisTemplate.expire(metaKey, ttl);
        stringRedisTemplate.expire(contentKey(streamId), ttl);
        return streamId;
    }

    public void appendToken(String streamId, String token) {
        if (!enabled(streamId) || token == null || token.isBlank()) {
            return;
        }

        stringRedisTemplate.opsForValue().append(contentKey(streamId), token);
        stringRedisTemplate.opsForHash().put(metaKey(streamId), "updatedAt", Instant.now().toString());
    }

    public void complete(String streamId) {
        updateStatus(streamId, STATUS_COMPLETED, null);
    }

    public void fail(String streamId, String errorMessage) {
        updateStatus(streamId, STATUS_FAILED, errorMessage);
    }

    public StreamSessionSnapshot getSnapshot(String streamId) {
        if (!enabled(streamId)) {
            return null;
        }

        Map<Object, Object> meta = stringRedisTemplate.opsForHash().entries(metaKey(streamId));
        if (meta == null || meta.isEmpty()) {
            return null;
        }

        String content = stringRedisTemplate.opsForValue().get(contentKey(streamId));
        String status = (String) meta.getOrDefault("status", STATUS_STREAMING);
        Long conversationId = parseLong(meta.get("conversationId"));
        Long userId = parseLong(meta.get("userId"));
        Long assistantMessageId = parseLong(meta.get("assistantMessageId"));
        String updatedAt = (String) meta.get("updatedAt");
        String error = (String) meta.get("error");

        return new StreamSessionSnapshot(
                streamId,
                conversationId,
                userId,
                assistantMessageId,
                status,
                content == null ? "" : content,
                updatedAt,
                error
        );
    }

    private void updateStatus(String streamId, String status, String errorMessage) {
        if (!enabled(streamId)) {
            return;
        }

        stringRedisTemplate.opsForHash().put(metaKey(streamId), "status", status);
        stringRedisTemplate.opsForHash().put(metaKey(streamId), "updatedAt", Instant.now().toString());
        if (errorMessage != null && !errorMessage.isBlank()) {
            stringRedisTemplate.opsForHash().put(metaKey(streamId), "error", errorMessage);
        }
    }

    private boolean enabled(String streamId) {
        return streamSessionProperties.isEnabled() && streamId != null && !streamId.isBlank();
    }

    private String metaKey(String streamId) {
        return "jarvis:stream:meta:" + streamId;
    }

    private String contentKey(String streamId) {
        return "jarvis:stream:content:" + streamId;
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        return Long.parseLong(String.valueOf(value));
    }

    public record StreamSessionSnapshot(
            String streamId,
            Long conversationId,
            Long userId,
            Long assistantMessageId,
            String status,
            String content,
            String updatedAt,
            String error
    ) {
    }
}
