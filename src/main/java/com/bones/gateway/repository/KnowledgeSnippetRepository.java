package com.bones.gateway.repository;

import com.bones.gateway.entity.KnowledgeSnippet;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeSnippetRepository extends JpaRepository<KnowledgeSnippet, Long> {

    List<KnowledgeSnippet> findTop200ByWorkspaceIdOrderByUpdatedAtDesc(Long workspaceId);
}
