package com.bones.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EchoRequest(
        @NotBlank(message = "content must not be blank")
        @Size(max = 500, message = "content length must be <= 500")
        String content
) {
}
