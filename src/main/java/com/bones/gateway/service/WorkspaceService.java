package com.bones.gateway.service;

import com.bones.gateway.common.BusinessException;
import com.bones.gateway.common.ErrorCode;
import com.bones.gateway.entity.Workspace;
import com.bones.gateway.entity.WorkspaceMember;
import com.bones.gateway.entity.WorkspaceMemberRole;
import com.bones.gateway.repository.WorkspaceMemberRepository;
import com.bones.gateway.repository.WorkspaceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceService {

    private static final String PERSONAL_WORKSPACE_PREFIX = "个人空间-";

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;

    public WorkspaceService(WorkspaceRepository workspaceRepository,
                            WorkspaceMemberRepository workspaceMemberRepository) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
    }

    @Transactional
    public Long resolveWorkspaceId(Long requestedWorkspaceId, Long userId) {
        if (userId == null) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "userId must not be null",
                    HttpStatus.BAD_REQUEST
            );
        }
        if (requestedWorkspaceId == null) {
            return getOrCreatePersonalWorkspaceId(userId);
        }
        assertMember(requestedWorkspaceId, userId);
        return requestedWorkspaceId;
    }

    @Transactional(readOnly = true)
    public void assertMember(Long workspaceId, Long userId) {
        loadMember(workspaceId, userId);
    }

    @Transactional(readOnly = true)
    public void assertCanWrite(Long workspaceId, Long userId) {
        WorkspaceMember member = loadMember(workspaceId, userId);
        if (member.getRole() == WorkspaceMemberRole.VIEWER) {
            throw new BusinessException(
                    ErrorCode.FORBIDDEN,
                    ErrorCode.FORBIDDEN.getMessage(),
                    HttpStatus.FORBIDDEN
            );
        }
    }

    @Transactional(readOnly = true)
    public void assertCanManage(Long workspaceId, Long userId) {
        WorkspaceMember member = loadMember(workspaceId, userId);
        if (member.getRole() != WorkspaceMemberRole.OWNER
                && member.getRole() != WorkspaceMemberRole.ADMIN) {
            throw new BusinessException(
                    ErrorCode.FORBIDDEN,
                    ErrorCode.FORBIDDEN.getMessage(),
                    HttpStatus.FORBIDDEN
            );
        }
    }

    @Transactional
    public Long getOrCreatePersonalWorkspaceId(Long userId) {
        return workspaceRepository.findFirstByOwnerUserIdAndPersonalTrueOrderByIdAsc(userId)
                .map(Workspace::getId)
                .orElseGet(() -> createPersonalWorkspace(userId).getId());
    }

    private Workspace createPersonalWorkspace(Long userId) {
        Workspace workspace = workspaceRepository.save(Workspace.builder()
                .name(PERSONAL_WORKSPACE_PREFIX + userId)
                .ownerUserId(userId)
                .personal(true)
                .build());

        workspaceMemberRepository.save(WorkspaceMember.builder()
                .workspaceId(workspace.getId())
                .userId(userId)
                .role(WorkspaceMemberRole.OWNER)
                .build());
        return workspace;
    }

    private WorkspaceMember loadMember(Long workspaceId, Long userId) {
        if (workspaceId == null) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "workspaceId must not be null",
                    HttpStatus.BAD_REQUEST
            );
        }
        if (userId == null) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "userId must not be null",
                    HttpStatus.BAD_REQUEST
            );
        }
        if (!workspaceRepository.existsById(workspaceId)) {
            throw new BusinessException(
                    ErrorCode.WORKSPACE_NOT_FOUND,
                    "workspace not found: " + workspaceId,
                    HttpStatus.NOT_FOUND
            );
        }
        return workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.FORBIDDEN,
                        ErrorCode.FORBIDDEN.getMessage(),
                        HttpStatus.FORBIDDEN
                ));
    }
}
