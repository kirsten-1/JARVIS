package com.bones.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import com.bones.gateway.common.BusinessException;
import com.bones.gateway.common.ErrorCode;
import com.bones.gateway.dto.AgentRunRequest;
import com.bones.gateway.dto.AgentRunResponse;
import com.bones.gateway.dto.MessageItemResponse;
import com.bones.gateway.dto.MetricsOverviewResponse;
import com.bones.gateway.entity.Conversation;
import com.bones.gateway.entity.ConversationStatus;
import com.bones.gateway.entity.Message;
import com.bones.gateway.entity.MessageRole;
import com.bones.gateway.integration.ai.AiServiceClient;
import com.bones.gateway.integration.ai.model.AiChatMessage;
import com.bones.gateway.integration.ai.model.AiChatRequest;
import com.bones.gateway.integration.ai.model.AiChatResponse;
import com.bones.gateway.integration.ai.model.AiToolCall;
import com.bones.gateway.service.BillingService.BillingUsage;
import com.bones.gateway.service.agent.AgentToolRegistry;
import com.bones.gateway.service.agent.ConversationDigestAgentTool;
import com.bones.gateway.service.agent.TimeNowAgentTool;
import com.bones.gateway.service.agent.WorkspaceMetricsOverviewAgentTool;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.ArgumentMatcher;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.OngoingStubbing;
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
    private AgentToolRegistry agentToolRegistry;

    @BeforeEach
    void setUp() {
        agentToolRegistry = new AgentToolRegistry(List.of(
                new TimeNowAgentTool(),
                new WorkspaceMetricsOverviewAgentTool(opsMetricsService),
                new ConversationDigestAgentTool(messageService)
        ));
        agentOrchestrationService = new AgentOrchestrationService(
                conversationService,
                messageService,
                conversationContextService,
                aiServiceClient,
                requestGuardService,
                billingService,
                tokenEstimator,
                opsMetricsService,
                agentToolRegistry
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

    @Test
    void run_shouldSupportLlmJsonPlannerModeWithToolAllowlist() {
        Conversation conversation = Conversation.builder()
                .id(11L)
                .userId(1001L)
                .workspaceId(1L)
                .title("新会话")
                .status(ConversationStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(conversationService.getUserConversation(11L, 1001L)).thenReturn(conversation);
        when(conversationContextService.buildPromptMessages(eq(11L), any()))
                .thenReturn(List.of(new AiChatMessage("system", "ctx")));
        when(messageService.saveMessage(eq(11L), eq(MessageRole.USER), any(), eq(null)))
                .thenReturn(Message.builder().id(201L).conversationId(11L).role(MessageRole.USER).content("q").build());
        when(messageService.saveMessage(eq(11L), eq(MessageRole.ASSISTANT), any(), eq(null)))
                .thenReturn(Message.builder().id(202L).conversationId(11L).role(MessageRole.ASSISTANT).content("a").build());
        when(aiServiceClient.chat(any(AiChatRequest.class)))
                .thenReturn(Mono.just(new AiChatResponse(
                        "{\"tools\":[{\"name\":\"time_now\"},{\"name\":\"workspace_metrics_overview\"}]}",
                        "glm-4.6v-flashx",
                        "stop"
                )))
                .thenReturn(Mono.just(new AiChatResponse("final answer", "glm-4.6v-flashx", "stop")));
        when(tokenEstimator.estimateMessagesTokens(any())).thenReturn(30);
        when(tokenEstimator.estimateTextTokens(eq("final answer"))).thenReturn(10);
        when(billingService.recordUsage(eq(1001L), eq(1L), any(), any(), eq(30), eq(10), eq(40), any()))
                .thenReturn(new BillingUsage(30, 10, 0.003));

        AgentRunResponse response = agentOrchestrationService.run(
                11L,
                new AgentRunRequest(
                        1001L,
                        "请先看时间和运营指标，然后给结论",
                        "glm",
                        "glm-4.6v-flashx",
                        3,
                        Map.of(
                                "plannerMode", "llm_json",
                                "allowedTools", List.of("time_now", "workspace_metrics_overview")
                        )
                )
        );

        assertEquals(2, response.steps().size());
        assertEquals("time_now", response.steps().get(0).tool());
        assertEquals("workspace_metrics_overview", response.steps().get(1).tool());
        verify(aiServiceClient, times(2)).chat(any(AiChatRequest.class));
        verify(aiServiceClient).chat(argThat(new ArgumentMatcher<>() {
            @Override
            public boolean matches(AiChatRequest request) {
                return request != null
                        && request.message() != null
                        && request.message().contains("你是 Jarvis Planner")
                        && request.message().contains("可用工具")
                        && request.metadata() != null
                && "m14-llm-json".equals(request.metadata().get("agentPlanner"));
            }
        }));
    }

    @Test
    void run_shouldSupportNativeFunctionCallingPlannerMode() {
        Conversation conversation = Conversation.builder()
                .id(14L)
                .userId(1001L)
                .workspaceId(1L)
                .title("新会话")
                .status(ConversationStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(conversationService.getUserConversation(14L, 1001L)).thenReturn(conversation);
        when(conversationContextService.buildPromptMessages(eq(14L), any()))
                .thenReturn(List.of(new AiChatMessage("system", "ctx")));
        when(messageService.saveMessage(eq(14L), eq(MessageRole.USER), any(), eq(null)))
                .thenReturn(Message.builder().id(211L).conversationId(14L).role(MessageRole.USER).content("q").build());
        when(messageService.saveMessage(eq(14L), eq(MessageRole.ASSISTANT), any(), eq(null)))
                .thenReturn(Message.builder().id(212L).conversationId(14L).role(MessageRole.ASSISTANT).content("a").build());
        when(messageService.listMessages(eq(14L), eq(1001L))).thenReturn(List.of(
                new MessageItemResponse(1L, 14L, MessageRole.USER, "hi", null, LocalDateTime.now()),
                new MessageItemResponse(2L, 14L, MessageRole.ASSISTANT, "hello", null, LocalDateTime.now())
        ));
        when(aiServiceClient.chat(any(AiChatRequest.class)))
                .thenReturn(Mono.just(new AiChatResponse(
                        "",
                        "gpt-4o-mini",
                        "tool_calls",
                        null,
                        null,
                        null,
                        List.of(
                                new AiToolCall("call_1", "time_now", "{}"),
                                new AiToolCall("call_2", "conversation_digest", "{\"limit\":2}")
                        )
                )))
                .thenReturn(Mono.just(new AiChatResponse("final answer", "gpt-4o-mini", "stop")));
        when(tokenEstimator.estimateMessagesTokens(any())).thenReturn(32);
        when(tokenEstimator.estimateTextTokens(eq("final answer"))).thenReturn(12);
        when(billingService.recordUsage(eq(1001L), eq(1L), any(), any(), eq(32), eq(12), eq(44), any()))
                .thenReturn(new BillingUsage(32, 12, 0.003));

        AgentRunResponse response = agentOrchestrationService.run(
                14L,
                new AgentRunRequest(
                        1001L,
                        "先获取当前时间并回顾最近会话，再给结论",
                        "glm",
                        "glm-4.6v-flashx",
                        3,
                        Map.of(
                                "plannerMode", "function_calling",
                                "allowedTools", List.of("time_now", "conversation_digest")
                        )
                )
        );

        assertEquals(2, response.steps().size());
        assertEquals("time_now", response.steps().get(0).tool());
        assertEquals("conversation_digest", response.steps().get(1).tool());
        verify(aiServiceClient, times(3)).chat(any(AiChatRequest.class));
        verify(aiServiceClient, times(2)).chat(argThat(new ArgumentMatcher<>() {
            @Override
            public boolean matches(AiChatRequest request) {
                return request != null
                        && request.metadata() != null
                        && "m14-function-calling".equals(request.metadata().get("agentPlanner"))
                        && request.metadata().containsKey("openaiTools")
                        && request.metadata().containsKey("openaiToolChoice");
            }
        }));
    }

    @Test
    void run_shouldRetryToolWhenConfigured() {
        Conversation conversation = Conversation.builder()
                .id(12L)
                .userId(1001L)
                .workspaceId(1L)
                .title("新会话")
                .status(ConversationStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(conversationService.getUserConversation(12L, 1001L)).thenReturn(conversation);
        when(conversationContextService.buildPromptMessages(eq(12L), any()))
                .thenReturn(List.of(new AiChatMessage("system", "ctx")));
        when(messageService.saveMessage(eq(12L), eq(MessageRole.USER), any(), eq(null)))
                .thenReturn(Message.builder().id(301L).conversationId(12L).role(MessageRole.USER).content("q").build());
        when(messageService.saveMessage(eq(12L), eq(MessageRole.ASSISTANT), any(), eq(null)))
                .thenReturn(Message.builder().id(302L).conversationId(12L).role(MessageRole.ASSISTANT).content("a").build());
        when(aiServiceClient.chat(any(AiChatRequest.class)))
                .thenReturn(Mono.just(new AiChatResponse("ok", "glm-4.6v-flashx", "stop")));
        when(tokenEstimator.estimateMessagesTokens(any())).thenReturn(20);
        when(tokenEstimator.estimateTextTokens(eq("ok"))).thenReturn(10);
        when(billingService.recordUsage(eq(1001L), eq(1L), any(), any(), eq(20), eq(10), eq(30), any()))
                .thenReturn(new BillingUsage(20, 10, 0.001));

        OngoingStubbing<MetricsOverviewResponse> stubbing = when(opsMetricsService.getOverview(eq(1L), eq(null), eq(null)));
        stubbing.thenThrow(new RuntimeException("temporary error"))
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

        AgentRunResponse response = agentOrchestrationService.run(
                12L,
                new AgentRunRequest(
                        1001L,
                        "请给出工作区运营指标",
                        "glm",
                        "glm-4.6v-flashx",
                        3,
                        Map.of("toolMaxRetries", 1)
                )
        );

        assertEquals(1, response.steps().size());
        assertEquals("workspace_metrics_overview", response.steps().get(0).tool());
        assertEquals("success", response.steps().get(0).status());
        verify(opsMetricsService, times(2)).getOverview(eq(1L), eq(null), eq(null));
    }

    @Test
    void run_shouldMarkStepFailedWhenToolTimeout() {
        Conversation conversation = Conversation.builder()
                .id(13L)
                .userId(1001L)
                .workspaceId(1L)
                .title("新会话")
                .status(ConversationStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(conversationService.getUserConversation(13L, 1001L)).thenReturn(conversation);
        when(conversationContextService.buildPromptMessages(eq(13L), any()))
                .thenReturn(List.of(new AiChatMessage("system", "ctx")));
        when(messageService.saveMessage(eq(13L), eq(MessageRole.USER), any(), eq(null)))
                .thenReturn(Message.builder().id(401L).conversationId(13L).role(MessageRole.USER).content("q").build());
        when(messageService.saveMessage(eq(13L), eq(MessageRole.ASSISTANT), any(), eq(null)))
                .thenReturn(Message.builder().id(402L).conversationId(13L).role(MessageRole.ASSISTANT).content("a").build());
        when(aiServiceClient.chat(any(AiChatRequest.class)))
                .thenReturn(Mono.just(new AiChatResponse("still respond", "glm-4.6v-flashx", "stop")));
        when(tokenEstimator.estimateMessagesTokens(any())).thenReturn(20);
        when(tokenEstimator.estimateTextTokens(eq("still respond"))).thenReturn(10);
        when(billingService.recordUsage(eq(1001L), eq(1L), any(), any(), eq(20), eq(10), eq(30), any()))
                .thenReturn(new BillingUsage(20, 10, 0.001));
        when(messageService.listMessages(eq(13L), eq(1001L))).thenAnswer(invocation -> {
            try {
                Thread.sleep(700);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        });

        AgentRunResponse response = agentOrchestrationService.run(
                13L,
                new AgentRunRequest(
                        1001L,
                        "请做一下会话摘要",
                        "glm",
                        "glm-4.6v-flashx",
                        3,
                        Map.of("toolTimeoutMs", 200, "allowedTools", List.of("conversation_digest"))
                )
        );

        assertEquals(1, response.steps().size());
        assertEquals("conversation_digest", response.steps().get(0).tool());
        assertEquals("failed", response.steps().get(0).status());
        assertTrue(response.steps().get(0).output().contains("tool execution failed"));
        assertEquals("still respond", response.assistantContent());
    }
}
