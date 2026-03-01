package com.bones.gateway.repository;

import com.bones.gateway.entity.Workspace;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    Optional<Workspace> findFirstByOwnerUserIdAndPersonalTrueOrderByIdAsc(Long ownerUserId);
}
