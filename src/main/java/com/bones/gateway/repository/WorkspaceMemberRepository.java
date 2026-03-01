package com.bones.gateway.repository;

import com.bones.gateway.entity.WorkspaceMember;
import com.bones.gateway.entity.WorkspaceMemberRole;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, Long> {

    boolean existsByWorkspaceIdAndUserId(Long workspaceId, Long userId);

    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(Long workspaceId, Long userId);

    long countByWorkspaceIdAndRoleIn(Long workspaceId, Iterable<WorkspaceMemberRole> roles);
}
