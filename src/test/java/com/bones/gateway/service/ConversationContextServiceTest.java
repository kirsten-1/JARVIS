package com.bones.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.bones.gateway.config.ConversationContextProperties;
import com.bones.gateway.entity.Message;
import com.bones.gateway.entity.MessageRole;
import com.bones.gateway.integration.ai.model.AiChatMessage;
import com.bones.gateway.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class ConversationContextServiceTest {

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private ConversationContextService conversationContextService;

    @BeforeEach
    void setUp() {
        ConversationContextProperties properties = new ConversationContextProperties();
        properties.setCacheEnabled(false);
        properties.setWindowSize(3);
        properties.setCacheTtlSeconds(1800);

        conversationContextService = new ConversationContextService(
                properties,
                messageRepository,
                stringRedisTemplate,
                new ObjectMapper()
        );
    }

    @Test
    void buildPromptMessages_shouldUseRecentHistoryAndAppendUserInput() {
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(anyLong())).thenReturn(List.of(
                Message.builder().conversationId(1L).role(MessageRole.USER).content("u1").createdAt(LocalDateTime.now().minusMinutes(3)).build(),
                Message.builder().conversationId(1L).role(MessageRole.ASSISTANT).content("a1").createdAt(LocalDateTime.now().minusMinutes(2)).build(),
                Message.builder().conversationId(1L).role(MessageRole.USER).content("u2").createdAt(LocalDateTime.now().minusMinutes(1)).build()
        ));

        List<AiChatMessage> prompt = conversationContextService.buildPromptMessages(1L, "latest");

        assertEquals(3, prompt.size());
        assertEquals("assistant", prompt.get(0).role());
        assertEquals("a1", prompt.get(0).content());
        assertEquals("user", prompt.get(2).role());
        assertEquals("latest", prompt.get(2).content());
    }
}
