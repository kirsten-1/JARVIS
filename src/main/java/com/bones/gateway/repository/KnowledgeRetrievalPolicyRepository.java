package com.bones.gateway.repository;

import com.bones.gateway.entity.KnowledgeRetrievalPolicy;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeRetrievalPolicyRepository extends JpaRepository<KnowledgeRetrievalPolicy, Long> {

    Optional<KnowledgeRetrievalPolicy> findByWorkspaceId(Long workspaceId);
}
