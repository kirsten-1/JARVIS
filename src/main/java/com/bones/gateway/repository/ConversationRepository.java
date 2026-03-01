package com.bones.gateway.repository;

import com.bones.gateway.entity.Conversation;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findById(Long id);

    Page<Conversation> findAllByWorkspaceIdOrderByUpdatedAtDesc(Long workspaceId, Pageable pageable);
}
