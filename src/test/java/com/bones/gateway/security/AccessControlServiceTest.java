package com.bones.gateway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bones.gateway.common.BusinessException;
import com.bones.gateway.common.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class AccessControlServiceTest {

    private final AccessControlService accessControlService;

    AccessControlServiceTest() {
        SecurityProperties securityProperties = new SecurityProperties();
        securityProperties.setEnabled(true);
        this.accessControlService = new AccessControlService(securityProperties);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolveUserId_shouldUseCurrentUserWhenRequestNull() {
        setAuth(new AuthenticatedUser(1001L, UserRole.USER));

        Long resolved = accessControlService.resolveUserId(null);

        assertEquals(1001L, resolved);
    }

    @Test
    void resolveUserId_shouldRejectOtherUserForNormalRole() {
        setAuth(new AuthenticatedUser(1001L, UserRole.USER));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> accessControlService.resolveUserId(1002L));

        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void resolveUserId_shouldAllowAdminActAsOtherUser() {
        setAuth(new AuthenticatedUser(1L, UserRole.ADMIN));

        Long resolved = accessControlService.resolveUserId(2002L);

        assertEquals(2002L, resolved);
    }

    private void setAuth(AuthenticatedUser user) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                user,
                "token",
                List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
