package com.bones.gateway.service;

import com.bones.gateway.config.M6CacheProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReadCacheService {

    private static final Logger log = LoggerFactory.getLogger(ReadCacheService.class);

    private final M6CacheProperties cacheProperties;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ReadCacheService(M6CacheProperties cacheProperties,
                            StringRedisTemplate stringRedisTemplate,
                            ObjectMapper objectMapper,
                            MeterRegistry meterRegistry) {
        this.cacheProperties = cacheProperties;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public <T> T get(String key,
                     Duration ttl,
                     TypeReference<T> typeReference,
                     Supplier<T> loader,
                     String cacheName) {
        if (!cacheProperties.isEnabled()) {
            return loader.get();
        }

        String cached = stringRedisTemplate.opsForValue().get(key);
        if (cached != null && !cached.isBlank()) {
            try {
                T value = objectMapper.readValue(cached, typeReference);
                meterRegistry.counter("jarvis.cache.read.hit", "name", cacheName).increment();
                return value;
            } catch (Exception ex) {
                meterRegistry.counter("jarvis.cache.read.deserialize_error", "name", cacheName).increment();
                log.warn("read cache deserialize failed, key={}", key, ex);
            }
        }

        meterRegistry.counter("jarvis.cache.read.miss", "name", cacheName).increment();
        T loaded = loader.get();
        if (loaded != null) {
            try {
                stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(loaded), ttl);
            } catch (Exception ex) {
                meterRegistry.counter("jarvis.cache.read.write_error", "name", cacheName).increment();
                log.warn("read cache write failed, key={}", key, ex);
            }
        }
        return loaded;
    }

    public void evictByPrefix(String prefix, String cacheName) {
        if (!cacheProperties.isEnabled()) {
            return;
        }
        try {
            Set<String> keys = stringRedisTemplate.keys(prefix + "*");
            if (keys == null || keys.isEmpty()) {
                return;
            }
            stringRedisTemplate.delete(keys);
            meterRegistry.counter("jarvis.cache.read.evict", "name", cacheName).increment(keys.size());
        } catch (Exception ex) {
            meterRegistry.counter("jarvis.cache.read.evict_error", "name", cacheName).increment();
            log.warn("read cache evict failed, prefix={}", prefix, ex);
        }
    }
}
