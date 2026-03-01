package com.bones.gateway.security;

import com.bones.gateway.common.BusinessException;
import com.bones.gateway.common.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AccessControlService {

    private final SecurityProperties securityProperties;

    public AccessControlService(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    public AuthenticatedUser currentUser() {
        if (!securityProperties.isEnabled()) {
            return new AuthenticatedUser(-1L, UserRole.ADMIN);
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.getMessage(), HttpStatus.UNAUTHORIZED);
        }
        return user;
    }

    public Long resolveUserId(Long requestedUserId) {
        if (!securityProperties.isEnabled()) {
            if (requestedUserId == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "userId must not be null when security is disabled", HttpStatus.BAD_REQUEST);
            }
            return requestedUserId;
        }

        AuthenticatedUser user = currentUser();
        if (requestedUserId == null) {
            return user.userId();
        }
        if (user.isAdmin() || requestedUserId.equals(user.userId())) {
            return requestedUserId;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getMessage(), HttpStatus.FORBIDDEN);
    }
}
