package com.bones.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {

    private static final String CLAIM_ROLE = "role";

    private final SecurityProperties securityProperties;

    public JwtTokenService(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    public String generateToken(Long userId, UserRole role) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(securityProperties.getExpireSeconds());

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuer(securityProperties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .claim(CLAIM_ROLE, role.name())
                .signWith(signingKey())
                .compact();
    }

    public AuthenticatedUser parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey())
                .requireIssuer(securityProperties.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Long userId = Long.parseLong(claims.getSubject());
        String roleValue = claims.get(CLAIM_ROLE, String.class);
        UserRole role = roleValue == null ? UserRole.USER : UserRole.valueOf(roleValue);
        return new AuthenticatedUser(userId, role);
    }

    private SecretKey signingKey() {
        byte[] keyBytes = securityProperties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
