package com.bones.gateway.common;

public enum ErrorCode {
    SUCCESS(0, "success"),
    UNAUTHORIZED(4010, "unauthorized"),
    FORBIDDEN(4030, "forbidden"),
    BAD_REQUEST(4000, "bad request"),
    VALIDATION_FAILED(4001, "validation failed"),
    BUSINESS_ERROR(4002, "business error"),
    AI_PROVIDER_NOT_FOUND(4003, "ai provider not found"),
    AI_PROVIDER_DISABLED(4004, "ai provider disabled"),
    CONVERSATION_NOT_FOUND(4101, "conversation not found"),
    CONVERSATION_ARCHIVED(4102, "conversation archived"),
    WORKSPACE_NOT_FOUND(4201, "workspace not found"),
    RATE_LIMITED(4291, "rate limited"),
    QUOTA_EXCEEDED(4292, "quota exceeded"),
    AI_SERVICE_TIMEOUT(5001, "ai service timeout"),
    AI_SERVICE_UNAVAILABLE(5002, "ai service unavailable"),
    AI_SERVICE_BAD_RESPONSE(5003, "ai service bad response"),
    AI_PROVIDER_CONFIG_INVALID(5004, "ai provider config invalid"),
    INTERNAL_ERROR(5000, "internal server error");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
