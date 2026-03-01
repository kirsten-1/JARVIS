package com.bones.gateway.controller;

import com.bones.gateway.common.ApiResponse;
import com.bones.gateway.common.BusinessException;
import com.bones.gateway.common.ErrorCode;
import com.bones.gateway.dto.EchoRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/demo")
public class DemoController {

    @GetMapping("/ping")
    public ApiResponse<String> ping() {
        return ApiResponse.success("pong");
    }

    @PostMapping("/echo")
    public ApiResponse<Map<String, Object>> echo(@Valid @RequestBody EchoRequest request) {
        return ApiResponse.success(Map.of(
                "content", request.content(),
                "length", request.content().length()
        ));
    }

    @GetMapping("/biz-error")
    public ApiResponse<Void> bizError() {
        throw new BusinessException(ErrorCode.BUSINESS_ERROR, "demo business exception");
    }

    @GetMapping("/validate")
    public ApiResponse<Integer> validate(@RequestParam("number") @Min(value = 1, message = "number must be >= 1") Integer number) {
        return ApiResponse.success(number);
    }
}
