package com.bones.gateway.integration.ai.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StreamMetaTokenCodecTest {

    @Test
    void encodeAndDecode_shouldKeepUsageFields() {
        String token = StreamMetaTokenCodec.encodeUsage(10, 6, 16);
        assertTrue(StreamMetaTokenCodec.isUsageToken(token));

        StreamMetaTokenCodec.Usage usage = StreamMetaTokenCodec.decodeUsage(token);
        assertNotNull(usage);
        assertEquals(10, usage.promptTokens());
        assertEquals(6, usage.completionTokens());
        assertEquals(16, usage.totalTokens());
    }

    @Test
    void decode_shouldReturnNullWhenFormatInvalid() {
        assertNull(StreamMetaTokenCodec.decodeUsage("__JARVIS_USAGE__:1,2"));
        assertNull(StreamMetaTokenCodec.decodeUsage("plain-text"));
        assertNull(StreamMetaTokenCodec.decodeUsage(StreamMetaTokenCodec.USAGE_TOKEN_PREFIX + ",,"));
    }
}
