package com.bones.gateway.common;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus httpStatus;
    private final String traceId;

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, HttpStatus.BAD_REQUEST, null);
    }

    public BusinessException(ErrorCode errorCode, String message, HttpStatus httpStatus) {
        this(errorCode, message, httpStatus, null);
    }

    public BusinessException(ErrorCode errorCode, String message, HttpStatus httpStatus, String traceId) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.traceId = traceId;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getTraceId() {
        return traceId;
    }
}
