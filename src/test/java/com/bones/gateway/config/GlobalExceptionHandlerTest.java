package com.bones.gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bones.gateway.common.ApiResponse;
import com.bones.gateway.common.BusinessException;
import com.bones.gateway.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleBusinessException_shouldUseExceptionHttpStatus() {
        BusinessException exception = new BusinessException(
                ErrorCode.AI_SERVICE_UNAVAILABLE,
                "ai service unavailable",
                HttpStatus.SERVICE_UNAVAILABLE
        );

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(exception);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(ErrorCode.AI_SERVICE_UNAVAILABLE.getCode(), response.getBody().code());
    }
}
