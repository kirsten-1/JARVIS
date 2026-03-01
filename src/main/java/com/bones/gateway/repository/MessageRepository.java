package com.bones.gateway.repository;

import com.bones.gateway.entity.Message;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    void deleteByConversationId(Long conversationId);
}
