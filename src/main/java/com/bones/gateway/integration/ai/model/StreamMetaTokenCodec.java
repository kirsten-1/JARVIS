package com.bones.gateway.integration.ai.model;

public final class StreamMetaTokenCodec {

    public static final String USAGE_TOKEN_PREFIX = "__JARVIS_USAGE__:";

    private StreamMetaTokenCodec() {
    }

    public static boolean isUsageToken(String token) {
        return token != null && token.startsWith(USAGE_TOKEN_PREFIX);
    }

    public static String encodeUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        return USAGE_TOKEN_PREFIX + encodePart(promptTokens) + "," + encodePart(completionTokens) + "," + encodePart(totalTokens);
    }

    public static Usage decodeUsage(String token) {
        if (!isUsageToken(token)) {
            return null;
        }
        String raw = token.substring(USAGE_TOKEN_PREFIX.length());
        String[] parts = raw.split(",", -1);
        if (parts.length != 3) {
            return null;
        }

        Integer promptTokens = decodePart(parts[0]);
        Integer completionTokens = decodePart(parts[1]);
        Integer totalTokens = decodePart(parts[2]);
        if (promptTokens == null && completionTokens == null && totalTokens == null) {
            return null;
        }
        return new Usage(promptTokens, completionTokens, totalTokens);
    }

    private static String encodePart(Integer value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Integer decodePart(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public record Usage(
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens
    ) {
    }
}
