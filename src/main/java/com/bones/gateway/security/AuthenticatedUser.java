package com.bones.gateway.security;

public record AuthenticatedUser(
        Long userId,
        UserRole role
) {

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }
}
