package com.bones.gateway.common;

import java.time.Instant;

public record ApiResponse<T>(
        int code,
        String message,
        T data,
        String traceId,
        long timestamp
) {

    public static <T> ApiResponse<T> success(T data) {
        return success(data, TraceIdContext.getTraceId());
    }

    public static <T> ApiResponse<T> success(T data, String traceId) {
        return new ApiResponse<>(
                ErrorCode.SUCCESS.getCode(),
                ErrorCode.SUCCESS.getMessage(),
                data,
                traceId,
                Instant.now().toEpochMilli()
        );
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode, String message) {
        return failure(errorCode, message, TraceIdContext.getTraceId());
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode, String message, String traceId) {
        return new ApiResponse<>(
                errorCode.getCode(),
                message,
                null,
                traceId,
                Instant.now().toEpochMilli()
        );
    }
}
