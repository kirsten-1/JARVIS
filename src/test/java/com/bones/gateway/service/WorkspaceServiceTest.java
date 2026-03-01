package com.bones.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bones.gateway.common.BusinessException;
import com.bones.gateway.common.ErrorCode;
import com.bones.gateway.entity.Workspace;
import com.bones.gateway.entity.WorkspaceMember;
import com.bones.gateway.entity.WorkspaceMemberRole;
import com.bones.gateway.repository.WorkspaceMemberRepository;
import com.bones.gateway.repository.WorkspaceRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    @Mock
    private WorkspaceRepository workspaceRepository;
    @Mock
    private WorkspaceMemberRepository workspaceMemberRepository;

    private WorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService(workspaceRepository, workspaceMemberRepository);
    }

    @Test
    void resolveWorkspaceId_shouldUseRequestedWorkspaceWhenMember() {
        when(workspaceRepository.existsById(200L)).thenReturn(true);
        when(workspaceMemberRepository.findByWorkspaceIdAndUserId(200L, 1001L))
                .thenReturn(Optional.of(WorkspaceMember.builder()
                        .workspaceId(200L)
                        .userId(1001L)
                        .role(WorkspaceMemberRole.MEMBER)
                        .build()));

        Long workspaceId = workspaceService.resolveWorkspaceId(200L, 1001L);

        assertEquals(200L, workspaceId);
        verify(workspaceRepository, never()).findFirstByOwnerUserIdAndPersonalTrueOrderByIdAsc(any());
    }

    @Test
    void resolveWorkspaceId_shouldCreatePersonalWorkspaceWhenMissing() {
        when(workspaceRepository.findFirstByOwnerUserIdAndPersonalTrueOrderByIdAsc(1001L))
                .thenReturn(Optional.empty());
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> {
            Workspace workspace = invocation.getArgument(0);
            workspace.setId(300L);
            return workspace;
        });

        Long workspaceId = workspaceService.resolveWorkspaceId(null, 1001L);

        assertEquals(300L, workspaceId);
        verify(workspaceMemberRepository).save(any());
    }

    @Test
    void assertMember_shouldThrowForbiddenWhenUserNotInWorkspace() {
        when(workspaceRepository.existsById(200L)).thenReturn(true);
        when(workspaceMemberRepository.findByWorkspaceIdAndUserId(eq(200L), eq(1001L)))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.assertMember(200L, 1001L));

        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void assertCanWrite_shouldRejectViewerRole() {
        when(workspaceRepository.existsById(200L)).thenReturn(true);
        when(workspaceMemberRepository.findByWorkspaceIdAndUserId(200L, 1001L))
                .thenReturn(Optional.of(WorkspaceMember.builder()
                        .workspaceId(200L)
                        .userId(1001L)
                        .role(WorkspaceMemberRole.VIEWER)
                        .build()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.assertCanWrite(200L, 1001L));

        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }
}
