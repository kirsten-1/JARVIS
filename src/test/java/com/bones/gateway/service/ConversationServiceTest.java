package com.bones.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bones.gateway.common.BusinessException;
import com.bones.gateway.common.ErrorCode;
import com.bones.gateway.dto.ConversationItemResponse;
import com.bones.gateway.dto.CreateConversationRequest;
import com.bones.gateway.entity.Conversation;
import com.bones.gateway.entity.ConversationStatus;
import com.bones.gateway.config.M6CacheProperties;
import com.bones.gateway.repository.ConversationRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private ReadCacheService readCacheService;
    @Mock
    private WorkspaceService workspaceService;

    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(conversationRepository, workspaceService, readCacheService, new M6CacheProperties());
    }

    @Test
    void createConversation_shouldUseDefaultTitleWhenBlank() {
        when(workspaceService.resolveWorkspaceId(88L, 1001L)).thenReturn(88L);
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> {
            Conversation conversation = invocation.getArgument(0);
            conversation.setId(1L);
            conversation.setCreatedAt(LocalDateTime.now());
            conversation.setUpdatedAt(LocalDateTime.now());
            return conversation;
        });

        ConversationItemResponse created = conversationService.createConversation(
                new CreateConversationRequest(1001L, 88L, "  "));

        assertEquals(1L, created.id());
        assertEquals(88L, created.workspaceId());
        assertEquals("新会话", created.title());
        assertEquals(ConversationStatus.ACTIVE, created.status());
    }

    @Test
    void getUserConversation_shouldThrowWhenNotFound() {
        when(conversationRepository.findById(eq(1L))).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> conversationService.getUserConversation(1L, 1001L));

        assertEquals(ErrorCode.CONVERSATION_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void archiveConversation_shouldSetArchivedStatus() {
        Conversation conversation = Conversation.builder()
                .id(10L)
                .userId(1001L)
                .workspaceId(88L)
                .title("demo")
                .status(ConversationStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(conversationRepository.findById(eq(10L))).thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ConversationItemResponse archived = conversationService.archiveConversation(10L, 1001L);

        verify(workspaceService).assertMember(88L, 1001L);
        verify(workspaceService).assertCanManage(88L, 1001L);
        assertEquals(ConversationStatus.ARCHIVED, archived.status());
    }
}
