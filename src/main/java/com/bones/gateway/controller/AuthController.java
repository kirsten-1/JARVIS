package com.bones.gateway.controller;

import com.bones.gateway.common.ApiResponse;
import com.bones.gateway.security.JwtTokenService;
import com.bones.gateway.security.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile({"dev", "test"})
@Validated
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final JwtTokenService jwtTokenService;

    public AuthController(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    @PostMapping("/dev-token")
    public ApiResponse<Map<String, Object>> issueDevToken(@Valid @RequestBody DevTokenRequest request) {
        UserRole role = request.role() == null ? UserRole.USER : request.role();
        String token = jwtTokenService.generateToken(request.userId(), role);
        return ApiResponse.success(Map.of(
                "token", token,
                "userId", request.userId(),
                "role", role.name(),
                "issuedAt", Instant.now().toString()
        ));
    }

    public record DevTokenRequest(
            @NotNull(message = "userId must not be null")
            Long userId,
            UserRole role
    ) {
    }
}
