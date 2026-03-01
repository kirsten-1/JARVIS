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
import com.bones.gateway.dto.ConversationChatRequest;
import com.bones.gateway.dto.ConversationChatResponse;
import com.bones.gateway.entity.Conversation;
import com.bones.gateway.entity.ConversationStatus;
import com.bones.gateway.entity.Message;
import com.bones.gateway.entity.MessageRole;
import com.bones.gateway.integration.ai.AiServiceClient;
import com.bones.gateway.integration.ai.model.AiChatMessage;
import com.bones.gateway.integration.ai.model.AiChatRequest;
import com.bones.gateway.integration.ai.model.AiChatResponse;
import com.bones.gateway.service.BillingService.BillingUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ConversationService conversationService;
    @Mock
    private MessageService messageService;
    @Mock
    private AiServiceClient aiServiceClient;
    @Mock
    private RequestGuardService requestGuardService;
    @Mock
    private ConversationContextService conversationContextService;
    @Mock
    private BillingService billingService;
    @Mock
    private TokenEstimator tokenEstimator;
    @Mock
    private StreamSessionService streamSessionService;
    @Mock
    private HotResponseCacheService hotResponseCacheService;
    @Mock
    private OpsMetricsService opsMetricsService;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(
                conversationService,
                messageService,
                aiServiceClient,
                requestGuardService,
                conversationContextService,
                billingService,
                tokenEstimator,
                streamSessionService,
                hotResponseCacheService,
                new SimpleMeterRegistry(),
                opsMetricsService
        );
    }

    @Test
    void chat_shouldPersistUserAndAssistantMessage() {
        Conversation conversation = Conversation.builder()
                .id(1L)
                .userId(1001L)
                .workspaceId(88L)
                .title("新会话")
                .status(ConversationStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(conversationService.getUserConversation(1L, 1001L)).thenReturn(conversation);
        when(conversationContextService.buildPromptMessages(1L, "hello"))
                .thenReturn(List.of(new AiChatMessage("user", "hello")));
        when(messageService.saveMessage(eq(1L), eq(MessageRole.USER), eq("hello"), eq(null)))
                .thenReturn(Message.builder().id(11L).conversationId(1L).role(MessageRole.USER).content("hello").build());
        when(aiServiceClient.chat(any(AiChatRequest.class)))
                .thenReturn(Mono.just(new AiChatResponse("world", "deepseek-chat", "stop")));
        when(messageService.saveMessage(eq(1L), eq(MessageRole.ASSISTANT), eq("world"), eq(null)))
                .thenReturn(Message.builder().id(12L).conversationId(1L).role(MessageRole.ASSISTANT).content("world").build());
        when(tokenEstimator.estimateMessagesTokens(any())).thenReturn(20);
        when(tokenEstimator.estimateTextTokens(eq("world"))).thenReturn(10);
        when(billingService.recordUsage(eq(1001L), eq(88L), any(), any(), eq(20), eq(10), eq(30), any()))
                .thenReturn(new BillingUsage(20, 10, 0.001));

        ConversationChatResponse response = chatService.chat(
                1L,
                new ConversationChatRequest(1001L, "hello", "deepseek", "deepseek-chat", Map.of("requestId", "req-1"))
        );

        assertEquals(1L, response.conversationId());
        assertEquals(11L, response.userMessageId());
        assertEquals(12L, response.assistantMessageId());
        assertEquals("world", response.assistantContent());

        verify(requestGuardService).checkAndConsume(1001L, "deepseek", "deepseek-chat");
        verify(conversationService).touchConversation(conversation);
        verify(conversationService).maybeAutoGenerateTitle(conversation, "hello");
        verify(conversationContextService).appendMessage(1L, MessageRole.USER, "hello");
        verify(conversationContextService).appendMessage(1L, MessageRole.ASSISTANT, "world");
        verify(hotResponseCacheService).put(any(AiChatRequest.class), any(AiChatResponse.class));
    }

    @Test
    void chat_shouldUseHotCacheWhenPresent() {
        Conversation conversation = Conversation.builder()
                .id(1L)
                .userId(1001L)
                .workspaceId(88L)
                .title("新会话")
                .status(ConversationStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(conversationService.getUserConversation(1L, 1001L)).thenReturn(conversation);
        when(conversationContextService.buildPromptMessages(1L, "hello"))
                .thenReturn(List.of(new AiChatMessage("user", "hello")));
        when(messageService.saveMessage(eq(1L), eq(MessageRole.USER), eq("hello"), eq(null)))
                .thenReturn(Message.builder().id(11L).conversationId(1L).role(MessageRole.USER).content("hello").build());
        when(hotResponseCacheService.getIfPresent(any(AiChatRequest.class)))
                .thenReturn(new AiChatResponse("cache-world", "deepseek-chat", "stop"));
        when(messageService.saveMessage(eq(1L), eq(MessageRole.ASSISTANT), eq("cache-world"), eq(null)))
                .thenReturn(Message.builder().id(12L).conversationId(1L).role(MessageRole.ASSISTANT).content("cache-world").build());
        when(tokenEstimator.estimateMessagesTokens(any())).thenReturn(20);
        when(tokenEstimator.estimateTextTokens(eq("cache-world"))).thenReturn(10);
        when(billingService.recordUsage(eq(1001L), eq(88L), any(), any(), eq(20), eq(10), eq(30), any()))
                .thenReturn(new BillingUsage(20, 10, 0.001));

        ConversationChatResponse response = chatService.chat(
                1L,
                new ConversationChatRequest(1001L, "hello", "deepseek", "deepseek-chat", Map.of("requestId", "req-cache"))
        );

        assertEquals("cache-world", response.assistantContent());
        verify(aiServiceClient, never()).chat(any(AiChatRequest.class));
        verify(hotResponseCacheService, never()).put(any(AiChatRequest.class), any(AiChatResponse.class));
    }

    @Test
    void chat_shouldThrowWhenConversationArchived() {
        Conversation conversation = Conversation.builder()
                .id(1L)
                .userId(1001L)
                .workspaceId(88L)
                .title("demo")
                .status(ConversationStatus.ARCHIVED)
                .build();

        when(conversationService.getUserConversation(1L, 1001L)).thenReturn(conversation);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> chatService.chat(1L, new ConversationChatRequest(1001L, "hello", null, null, null)));

        assertEquals(ErrorCode.CONVERSATION_ARCHIVED, ex.getErrorCode());
        verify(aiServiceClient, never()).chat(any());
    }

    @Test
    void chat_shouldThrowWhenAiResponseEmpty() {
        Conversation conversation = Conversation.builder()
                .id(1L)
                .userId(1001L)
                .workspaceId(88L)
                .title("demo")
                .status(ConversationStatus.ACTIVE)
                .build();

        when(conversationService.getUserConversation(1L, 1001L)).thenReturn(conversation);
        when(conversationContextService.buildPromptMessages(1L, "hello"))
                .thenReturn(List.of(new AiChatMessage("user", "hello")));
        when(messageService.saveMessage(eq(1L), eq(MessageRole.USER), eq("hello"), eq(null)))
                .thenReturn(Message.builder().id(11L).conversationId(1L).role(MessageRole.USER).content("hello").build());
        when(aiServiceClient.chat(any(AiChatRequest.class))).thenReturn(Mono.just(new AiChatResponse(" ", "demo", "stop")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> chatService.chat(1L, new ConversationChatRequest(1001L, "hello", null, null, null)));

        assertEquals(ErrorCode.AI_SERVICE_BAD_RESPONSE, ex.getErrorCode());
        verify(messageService, never()).saveMessage(eq(1L), eq(MessageRole.ASSISTANT), any(), eq(null));
    }

    @Test
    void chatStream_shouldPersistAssistantAfterStreamComplete() {
        Conversation conversation = Conversation.builder()
                .id(1L)
                .userId(1001L)
                .workspaceId(88L)
                .title("新会话")
                .status(ConversationStatus.ACTIVE)
                .build();

        when(conversationService.getUserConversation(1L, 1001L)).thenReturn(conversation);
        when(conversationContextService.buildPromptMessages(1L, "hello"))
                .thenReturn(List.of(new AiChatMessage("user", "hello")));
        when(messageService.saveMessage(eq(1L), eq(MessageRole.USER), eq("hello"), eq(null)))
                .thenReturn(Message.builder().id(11L).conversationId(1L).role(MessageRole.USER).content("hello").build());
        when(messageService.saveMessage(eq(1L), eq(MessageRole.ASSISTANT), eq(""), eq(null)))
                .thenReturn(Message.builder().id(12L).conversationId(1L).role(MessageRole.ASSISTANT).content("").build());
        when(aiServiceClient.chatStream(any(AiChatRequest.class)))
                .thenReturn(Flux.just("he", "llo"));
        when(streamSessionService.createSession(1L, 1001L, 12L)).thenReturn("stream-1");
        when(tokenEstimator.estimateMessagesTokens(any())).thenReturn(20);
        when(tokenEstimator.estimateTextTokens(eq("hello"))).thenReturn(10);
        when(billingService.recordUsage(eq(1001L), eq(88L), any(), any(), eq(20), eq(10), eq(30), any()))
                .thenReturn(new BillingUsage(20, 10, 0.001));

        ChatService.ChatStreamResult result = chatService.chatStream(
                1L,
                new ConversationChatRequest(1001L, "hello", "deepseek", "deepseek-chat", null)
        );

        List<String> tokens = result.tokenFlux().collectList().block();

        assertEquals("stream-1", result.streamId());
        assertEquals(12L, result.assistantMessageId());
        assertEquals(List.of("he", "llo"), tokens);

        verify(messageService).updateMessageContent(12L, "he");
        verify(messageService).updateMessageContent(12L, "hello");
        verify(streamSessionService).complete("stream-1");
        verify(conversationService).touchConversation(conversation);
        verify(conversationService).maybeAutoGenerateTitle(conversation, "hello");
    }

    @Test
    void chatStream_shouldUseActualUsageWhenUsageTokenPresent() {
        Conversation conversation = Conversation.builder()
                .id(1L)
                .userId(1001L)
                .workspaceId(88L)
                .title("新会话")
                .status(ConversationStatus.ACTIVE)
                .build();

        when(conversationService.getUserConversation(1L, 1001L)).thenReturn(conversation);
        when(conversationContextService.buildPromptMessages(1L, "hello"))
                .thenReturn(List.of(new AiChatMessage("user", "hello")));
        when(messageService.saveMessage(eq(1L), eq(MessageRole.USER), eq("hello"), eq(null)))
                .thenReturn(Message.builder().id(11L).conversationId(1L).role(MessageRole.USER).content("hello").build());
        when(messageService.saveMessage(eq(1L), eq(MessageRole.ASSISTANT), eq(""), eq(null)))
                .thenReturn(Message.builder().id(12L).conversationId(1L).role(MessageRole.ASSISTANT).content("").build());
        when(aiServiceClient.chatStream(any(AiChatRequest.class)))
                .thenReturn(Flux.just(
                        "he",
                        "llo",
                        ChatService.USAGE_TOKEN_PREFIX + "12,8,20"
                ));
        when(streamSessionService.createSession(1L, 1001L, 12L)).thenReturn("stream-1");
        when(tokenEstimator.estimateMessagesTokens(any())).thenReturn(30);
        when(tokenEstimator.estimateTextTokens(eq("hello"))).thenReturn(11);
        when(billingService.recordUsage(
                eq(1001L),
                eq(88L),
                any(),
                any(),
                eq(12),
                eq(8),
                eq(20),
                eq(BillingService.UsageSource.ACTUAL)))
                .thenReturn(new BillingUsage(12, 8, 0.001, BillingService.UsageSource.ACTUAL));

        ChatService.ChatStreamResult result = chatService.chatStream(
                1L,
                new ConversationChatRequest(1001L, "hello", "glm", "glm-4.6v-flashx", null)
        );

        List<String> tokens = result.tokenFlux().collectList().block();

        assertEquals(List.of("he", "llo"), tokens);
        verify(messageService).updateMessageContent(12L, "he");
        verify(messageService).updateMessageContent(12L, "hello");
    }
}
