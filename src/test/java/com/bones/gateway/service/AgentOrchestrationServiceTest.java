package com.bones.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bones.gateway.common.BusinessException;
import com.bones.gateway.common.ErrorCode;
import com.bones.gateway.dto.AgentRunRequest;
import com.bones.gateway.dto.AgentRunResponse;
import com.bones.gateway.dto.MetricsOverviewResponse;
import com.bones.gateway.entity.Conversation;
import com.bones.gateway.entity.ConversationStatus;
import com.bones.gateway.entity.Message;
import com.bones.gateway.entity.MessageRole;
import com.bones.gateway.integration.ai.AiServiceClient;
import com.bones.gateway.integration.ai.model.AiChatMessage;
import com.bones.gateway.integration.ai.model.AiChatRequest;
import com.bones.gateway.integration.ai.model.AiChatResponse;
import com.bones.gateway.service.BillingService.BillingUsage;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class AgentOrchestrationServiceTest {

    @Mock
    private ConversationService conversationService;
    @Mock
    private MessageService messageService;
    @Mock
    private ConversationContextService conversationContextService;
    @Mock
    private AiServiceClient aiServiceClient;
    @Mock
    private RequestGuardService requestGuardService;
    @Mock
    private BillingService billingService;
    @Mock
    private TokenEstimator tokenEstimator;
    @Mock
    private OpsMetricsService opsMetricsService;

    private AgentOrchestrationService agentOrchestrationService;

    @BeforeEach
    void setUp() {
        agentOrchestrationService = new AgentOrchestrationService(
                conversationService,
                messageService,
                conversationContextService,
                aiServiceClient,
                requestGuardService,
                billingService,
                tokenEstimator,
                opsMetricsService
        );
    }

    @Test
    void run_shouldExecutePlannedToolsAndPersistFinalAnswer() {
        Conversation conversation = Conversation.builder()
                .id(10L)
                .userId(1001L)
                .workspaceId(1L)
                .title("新会话")
                .status(ConversationStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(conversationService.getUserConversation(10L, 1001L)).thenReturn(conversation);
        when(opsMetricsService.getOverview(eq(1L), eq(null), eq(null)))
                .thenReturn(new MetricsOverviewResponse(
                        1L,
                        LocalDate.now().minusDays(6),
                        LocalDate.now(),
                        5,
                        4,
                        1,
                        0.8,
                        1,
                        0.2,
                        300.0,
                        700.0,
                        900.0,
                        List.of()
                ));
        when(conversationContextService.buildPromptMessages(eq(10L), any()))
                .thenReturn(List.of(new AiChatMessage("system", "ctx")));
        when(messageService.saveMessage(eq(10L), eq(MessageRole.USER), any(), eq(null)))
                .thenReturn(Message.builder().id(101L).conversationId(10L).role(MessageRole.USER).content("q").build());
        when(messageService.saveMessage(eq(10L), eq(MessageRole.ASSISTANT), any(), eq(null)))
                .thenReturn(Message.builder().id(102L).conversationId(10L).role(MessageRole.ASSISTANT).content("a").build());
        when(aiServiceClient.chat(any(AiChatRequest.class)))
                .thenReturn(Mono.just(new AiChatResponse("这是最终回答", "glm-4.6v-flashx", "stop")));
        when(tokenEstimator.estimateMessagesTokens(any())).thenReturn(20);
        when(tokenEstimator.estimateTextTokens(eq("这是最终回答"))).thenReturn(15);
        when(billingService.recordUsage(eq(1001L), eq(1L), any(), any(), eq(20), eq(15), eq(35), any()))
                .thenReturn(new BillingUsage(20, 15, 0.002));

        AgentRunResponse response = agentOrchestrationService.run(
                10L,
                new AgentRunRequest(
                        1001L,
                        "请告诉我现在时间，并给出当前工作区的运营指标摘要",
                        "glm",
                        "glm-4.6v-flashx",
                        3,
                        Map.of("requestId", "m13-test")
                )
        );

        assertEquals(10L, response.conversationId());
        assertEquals(101L, response.userMessageId());
        assertEquals(102L, response.assistantMessageId());
        assertEquals("这是最终回答", response.assistantContent());
        assertTrue(response.steps().size() >= 2);
        assertEquals("time_now", response.steps().get(0).tool());
        assertEquals("workspace_metrics_overview", response.steps().get(1).tool());

        ArgumentCaptor<AiChatRequest> captor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiServiceClient).chat(captor.capture());
        assertTrue(captor.getValue().message().contains("工具执行结果"));
        assertTrue(captor.getValue().message().contains("time_now"));
        assertTrue(captor.getValue().message().contains("workspace_metrics_overview"));

        verify(requestGuardService).checkAndConsume(1001L, "glm", "glm-4.6v-flashx");
        verify(opsMetricsService).recordSyncResult(1L, "glm", true);
    }

    @Test
    void run_shouldRejectArchivedConversation() {
        Conversation conversation = Conversation.builder()
                .id(10L)
                .userId(1001L)
                .workspaceId(1L)
                .title("归档会话")
                .status(ConversationStatus.ARCHIVED)
                .build();
        when(conversationService.getUserConversation(10L, 1001L)).thenReturn(conversation);

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> agentOrchestrationService.run(
                        10L,
                        new AgentRunRequest(1001L, "测试", "glm", "glm-4.6v-flashx", 2, null)
                )
        );

        assertEquals(ErrorCode.CONVERSATION_ARCHIVED, ex.getErrorCode());
        verify(aiServiceClient, never()).chat(any());
    }
}
