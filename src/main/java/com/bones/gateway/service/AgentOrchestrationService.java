package com.bones.gateway.service;

import com.bones.gateway.common.BusinessException;
import com.bones.gateway.common.ErrorCode;
import com.bones.gateway.dto.AgentRunRequest;
import com.bones.gateway.dto.AgentRunResponse;
import com.bones.gateway.dto.AgentStepResponse;
import com.bones.gateway.dto.ConversationChatRequest;
import com.bones.gateway.dto.MessageItemResponse;
import com.bones.gateway.entity.Conversation;
import com.bones.gateway.entity.ConversationStatus;
import com.bones.gateway.entity.Message;
import com.bones.gateway.entity.MessageRole;
import com.bones.gateway.integration.ai.AiServiceClient;
import com.bones.gateway.integration.ai.model.AiChatMessage;
import com.bones.gateway.integration.ai.model.AiChatRequest;
import com.bones.gateway.integration.ai.model.AiChatResponse;
import com.bones.gateway.service.BillingService.UsageSource;
import com.bones.gateway.dto.MetricsOverviewResponse;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AgentOrchestrationService {

    private static final String TOOL_TIME_NOW = "time_now";
    private static final String TOOL_WORKSPACE_METRICS = "workspace_metrics_overview";
    private static final String TOOL_CONVERSATION_DIGEST = "conversation_digest";

    private static final int DEFAULT_MAX_STEPS = 3;
    private static final int DEFAULT_DIGEST_LIMIT = 8;
    private static final int MAX_PROMPT_STEP_OUTPUT_CHARS = 1200;

    private final ConversationService conversationService;
    private final MessageService messageService;
    private final ConversationContextService conversationContextService;
    private final AiServiceClient aiServiceClient;
    private final RequestGuardService requestGuardService;
    private final BillingService billingService;
    private final TokenEstimator tokenEstimator;
    private final OpsMetricsService opsMetricsService;

    public AgentOrchestrationService(ConversationService conversationService,
                                     MessageService messageService,
                                     ConversationContextService conversationContextService,
                                     AiServiceClient aiServiceClient,
                                     RequestGuardService requestGuardService,
                                     BillingService billingService,
                                     TokenEstimator tokenEstimator,
                                     OpsMetricsService opsMetricsService) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.conversationContextService = conversationContextService;
        this.aiServiceClient = aiServiceClient;
        this.requestGuardService = requestGuardService;
        this.billingService = billingService;
        this.tokenEstimator = tokenEstimator;
        this.opsMetricsService = opsMetricsService;
    }

    public AgentRunResponse run(Long conversationId, AgentRunRequest request) {
        ConversationChatRequest bridgeRequest = new ConversationChatRequest(
                request.userId(),
                request.message(),
                request.provider(),
                request.model(),
                request.metadata()
        );
        Conversation conversation = getActiveConversation(conversationId, request.userId());
        requestGuardService.checkAndConsume(request.userId(), request.provider(), request.model());
        String requestedProvider = resolveProvider(bridgeRequest, null);

        try {
            String userInput = request.message().trim();
            int maxSteps = request.maxSteps() == null ? DEFAULT_MAX_STEPS : request.maxSteps();
            List<AgentStepResult> stepResults = executeToolPlan(
                    userInput,
                    conversationId,
                    request.userId(),
                    conversation.getWorkspaceId(),
                    maxSteps
            );

            String agentPrompt = buildAgentPrompt(userInput, stepResults);
            List<AiChatMessage> promptMessages = conversationContextService.buildPromptMessages(conversationId, agentPrompt);

            Message userMessage = messageService.saveMessage(
                    conversationId,
                    MessageRole.USER,
                    userInput,
                    null
            );
            conversationContextService.appendMessage(conversationId, MessageRole.USER, userInput);

            AiChatRequest aiRequest = new AiChatRequest(
                    agentPrompt,
                    conversationId,
                    request.userId(),
                    request.provider(),
                    request.model(),
                    promptMessages,
                    buildAgentMetadata(request.metadata(), stepResults)
            );
            AiChatResponse aiResponse = aiServiceClient.chat(aiRequest).block();
            if (aiResponse == null || !hasText(aiResponse.content())) {
                throw new BusinessException(
                        ErrorCode.AI_SERVICE_BAD_RESPONSE,
                        "agent final response is empty",
                        HttpStatus.BAD_GATEWAY
                );
            }

            Message assistantMessage = messageService.saveMessage(
                    conversationId,
                    MessageRole.ASSISTANT,
                    aiResponse.content(),
                    null
            );
            conversationContextService.appendMessage(conversationId, MessageRole.ASSISTANT, aiResponse.content());

            String resolvedProvider = resolveProvider(bridgeRequest, aiResponse);
            UsageDecision usageDecision = resolveUsageDecision(promptMessages, aiResponse);
            billingService.recordUsage(
                    request.userId(),
                    conversation.getWorkspaceId(),
                    resolvedProvider,
                    resolveModel(bridgeRequest, aiResponse),
                    usageDecision.promptTokens(),
                    usageDecision.completionTokens(),
                    usageDecision.totalTokens(),
                    usageDecision.usageSource()
            );

            conversationService.touchConversation(conversation);
            conversationService.maybeAutoGenerateTitle(conversation, userInput);
            opsMetricsService.recordSyncResult(conversation.getWorkspaceId(), resolvedProvider, true);

            return new AgentRunResponse(
                    conversationId,
                    userMessage.getId(),
                    assistantMessage.getId(),
                    aiResponse.content(),
                    aiResponse.model(),
                    aiResponse.finishReason(),
                    stepResults.stream()
                            .map(this::toStepResponse)
                            .toList()
            );
        } catch (RuntimeException ex) {
            opsMetricsService.recordSyncResult(conversation.getWorkspaceId(), requestedProvider, false);
            throw ex;
        }
    }

    private List<AgentStepResult> executeToolPlan(String userInput,
                                                  Long conversationId,
                                                  Long userId,
                                                  Long workspaceId,
                                                  int maxSteps) {
        List<String> plannedTools = planTools(userInput, maxSteps);
        List<AgentStepResult> result = new ArrayList<>();
        for (int i = 0; i < plannedTools.size(); i++) {
            String tool = plannedTools.get(i);
            long startedAt = System.nanoTime();
            try {
                String toolInput = buildToolInput(tool, conversationId, workspaceId);
                String toolOutput = executeTool(tool, conversationId, userId, workspaceId);
                long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
                result.add(new AgentStepResult(i + 1, tool, "success", toolInput, toolOutput, durationMs));
            } catch (RuntimeException ex) {
                long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
                result.add(new AgentStepResult(
                        i + 1,
                        tool,
                        "failed",
                        buildToolInput(tool, conversationId, workspaceId),
                        truncate("tool execution failed: " + ex.getMessage(), MAX_PROMPT_STEP_OUTPUT_CHARS),
                        durationMs
                ));
            }
        }
        return result;
    }

    private List<String> planTools(String userInput, int maxSteps) {
        String normalized = userInput.toLowerCase(Locale.ROOT);
        List<String> tools = new ArrayList<>();

        if (containsAny(normalized, "时间", "几点", "time", "date", "today", "now")) {
            tools.add(TOOL_TIME_NOW);
        }
        if (containsAny(normalized, "指标", "运营", "metric", "success", "fallback", "ttft", "cost", "计费", "账单")) {
            tools.add(TOOL_WORKSPACE_METRICS);
        }
        if (containsAny(normalized, "总结", "摘要", "回顾", "history", "context", "上下文")) {
            tools.add(TOOL_CONVERSATION_DIGEST);
        }

        int safeMaxSteps = Math.max(1, Math.min(maxSteps, 6));
        if (tools.size() > safeMaxSteps) {
            return tools.subList(0, safeMaxSteps);
        }
        return tools;
    }

    private String buildToolInput(String tool, Long conversationId, Long workspaceId) {
        return switch (tool) {
            case TOOL_TIME_NOW -> "{}";
            case TOOL_WORKSPACE_METRICS -> "{\"workspaceId\":" + workspaceId + ",\"range\":\"last_7_days\"}";
            case TOOL_CONVERSATION_DIGEST -> "{\"conversationId\":" + conversationId + ",\"limit\":" + DEFAULT_DIGEST_LIMIT + "}";
            default -> "{}";
        };
    }

    private String executeTool(String tool, Long conversationId, Long userId, Long workspaceId) {
        return switch (tool) {
            case TOOL_TIME_NOW -> executeTimeNowTool();
            case TOOL_WORKSPACE_METRICS -> executeWorkspaceMetricsTool(workspaceId);
            case TOOL_CONVERSATION_DIGEST -> executeConversationDigestTool(conversationId, userId, DEFAULT_DIGEST_LIMIT);
            default -> "unsupported tool: " + tool;
        };
    }

    private String executeTimeNowTool() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        return "current_time=" + now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + ", timezone=Asia/Shanghai";
    }

    private String executeWorkspaceMetricsTool(Long workspaceId) {
        if (workspaceId == null) {
            return "workspace is null, metrics unavailable";
        }
        MetricsOverviewResponse overview = opsMetricsService.getOverview(workspaceId, null, null);
        return "workspaceId=" + overview.workspaceId()
                + ", totalRequests=" + overview.totalRequests()
                + ", successRate=" + overview.successRate()
                + ", fallbackRate=" + overview.fallbackRate()
                + ", ttftP50Ms=" + overview.ttftP50Ms()
                + ", ttftP90Ms=" + overview.ttftP90Ms();
    }

    private String executeConversationDigestTool(Long conversationId, Long userId, int limit) {
        List<MessageItemResponse> all = messageService.listMessages(conversationId, userId);
        if (all.isEmpty()) {
            return "no historical messages";
        }
        int fromIndex = Math.max(0, all.size() - Math.max(1, limit));
        List<MessageItemResponse> tail = all.subList(fromIndex, all.size());
        StringBuilder digest = new StringBuilder();
        for (MessageItemResponse item : tail) {
            String role = item.role() == null ? "unknown" : item.role().name();
            digest.append(role)
                    .append(": ")
                    .append(truncate(item.content(), 120))
                    .append('\n');
        }
        return truncate(digest.toString().trim(), MAX_PROMPT_STEP_OUTPUT_CHARS);
    }

    private String buildAgentPrompt(String userInput, List<AgentStepResult> steps) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是 Jarvis Agent 执行器。请基于用户问题和工具观察结果给出最终回答。\n");
        prompt.append("用户原始问题：\n").append(userInput).append("\n\n");
        if (steps.isEmpty()) {
            prompt.append("本轮未调用工具，请直接根据问题作答。\n");
        } else {
            prompt.append("工具执行结果：\n");
            for (AgentStepResult step : steps) {
                prompt.append("- step=").append(step.index())
                        .append(", tool=").append(step.tool())
                        .append(", status=").append(step.status())
                        .append(", output=").append(truncate(step.output(), MAX_PROMPT_STEP_OUTPUT_CHARS))
                        .append('\n');
            }
        }
        prompt.append("\n回答要求：\n");
        prompt.append("1) 先给结论，再给关键依据。\n");
        prompt.append("2) 如果工具结果存在冲突或失败，明确说明不确定性。\n");
        prompt.append("3) 使用简体中文，保持专业、简洁。\n");
        return prompt.toString();
    }

    private Map<String, Object> buildAgentMetadata(Map<String, Object> metadata, List<AgentStepResult> steps) {
        Map<String, Object> merged = new HashMap<>();
        if (metadata != null) {
            merged.putAll(metadata);
        }
        merged.put("agentMode", "m13-minimal");
        merged.put("agentStepCount", steps.size());
        merged.put("agentTools", steps.stream().map(AgentStepResult::tool).toList());
        return merged;
    }

    private AgentStepResponse toStepResponse(AgentStepResult result) {
        return new AgentStepResponse(
                result.index(),
                result.tool(),
                result.status(),
                result.input(),
                result.output(),
                result.durationMs()
        );
    }

    private Conversation getActiveConversation(Long conversationId, Long userId) {
        Conversation conversation = conversationService.getUserConversation(conversationId, userId);
        conversationService.assertCanWrite(conversation, userId);
        if (conversation.getStatus() != ConversationStatus.ACTIVE) {
            throw new BusinessException(
                    ErrorCode.CONVERSATION_ARCHIVED,
                    "conversation is archived: " + conversationId,
                    HttpStatus.BAD_REQUEST
            );
        }
        return conversation;
    }

    private UsageDecision resolveUsageDecision(List<AiChatMessage> promptMessages, AiChatResponse aiResponse) {
        int estimatedPrompt = tokenEstimator.estimateMessagesTokens(promptMessages);
        int estimatedCompletion = tokenEstimator.estimateTextTokens(aiResponse.content());

        Integer actualPrompt = aiResponse.promptTokens();
        Integer actualCompletion = aiResponse.completionTokens();
        Integer actualTotal = aiResponse.totalTokens();

        if (actualPrompt != null && actualCompletion != null) {
            int total = actualTotal == null ? actualPrompt + actualCompletion : actualTotal;
            return new UsageDecision(actualPrompt, actualCompletion, total, UsageSource.ACTUAL);
        }

        int total = estimatedPrompt + estimatedCompletion;
        return new UsageDecision(estimatedPrompt, estimatedCompletion, total, UsageSource.ESTIMATED);
    }

    private String resolveProvider(ConversationChatRequest request, AiChatResponse response) {
        if (request.provider() != null && !request.provider().isBlank()) {
            return request.provider();
        }
        if (response != null && response.model() != null && response.model().contains("/")) {
            return response.model().split("/")[0];
        }
        return "default";
    }

    private String resolveModel(ConversationChatRequest request, AiChatResponse response) {
        if (request.model() != null && !request.model().isBlank()) {
            return request.model();
        }
        if (response != null && response.model() != null && !response.model().isBlank()) {
            return response.model();
        }
        return "default";
    }

    private boolean hasText(String text) {
        return Objects.nonNull(text) && !text.isBlank();
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...";
    }

    private record AgentStepResult(
            int index,
            String tool,
            String status,
            String input,
            String output,
            long durationMs
    ) {
    }

    private record UsageDecision(
            int promptTokens,
            int completionTokens,
            int totalTokens,
            UsageSource usageSource
    ) {
    }
}
