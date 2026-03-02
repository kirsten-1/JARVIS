package com.bones.gateway.service;

import com.bones.gateway.common.BusinessException;
import com.bones.gateway.common.ErrorCode;
import com.bones.gateway.dto.AgentRunRequest;
import com.bones.gateway.dto.AgentRunResponse;
import com.bones.gateway.dto.AgentStepResponse;
import com.bones.gateway.dto.ConversationChatRequest;
import com.bones.gateway.entity.Conversation;
import com.bones.gateway.entity.ConversationStatus;
import com.bones.gateway.entity.Message;
import com.bones.gateway.entity.MessageRole;
import com.bones.gateway.integration.ai.AiServiceClient;
import com.bones.gateway.integration.ai.model.AiChatMessage;
import com.bones.gateway.integration.ai.model.AiChatRequest;
import com.bones.gateway.integration.ai.model.AiChatResponse;
import com.bones.gateway.integration.ai.model.AiToolCall;
import com.bones.gateway.service.agent.AgentTool;
import com.bones.gateway.service.agent.AgentToolContext;
import com.bones.gateway.service.agent.AgentToolIdempotencyService;
import com.bones.gateway.service.agent.AgentToolPolicyCenter;
import com.bones.gateway.service.agent.AgentToolPolicyCenter.ToolExecutionPolicy;
import com.bones.gateway.service.agent.AgentToolRegistry;
import com.bones.gateway.service.agent.ConversationDigestAgentTool;
import com.bones.gateway.service.agent.TimeNowAgentTool;
import com.bones.gateway.service.agent.WorkspaceMetricsOverviewAgentTool;
import com.bones.gateway.service.BillingService.UsageSource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class AgentOrchestrationService {

    private static final String TOOL_TIME_NOW = TimeNowAgentTool.NAME;
    private static final String TOOL_WORKSPACE_METRICS = WorkspaceMetricsOverviewAgentTool.NAME;
    private static final String TOOL_CONVERSATION_DIGEST = ConversationDigestAgentTool.NAME;
    private static final String PLANNER_MODE_RULE = "rule";
    private static final String PLANNER_MODE_LLM_JSON = "llm_json";
    private static final String PLANNER_MODE_FUNCTION_CALLING = "function_calling";
    private static final String METADATA_PLANNER_MODE = "plannerMode";
    private static final String METADATA_ALLOWED_TOOLS = "allowedTools";
    private static final String METADATA_TOOL_IDEMPOTENCY_KEY = "toolIdempotencyKey";
    private static final String METADATA_FC_MAX_ROUNDS = "functionCallingMaxRounds";

    private static final String META_OPENAI_TOOLS = "openaiTools";
    private static final String META_OPENAI_TOOL_CHOICE = "openaiToolChoice";
    private static final String META_OPENAI_PARALLEL_TOOL_CALLS = "openaiParallelToolCalls";
    private static final String META_OPENAI_RESPONSE_FORMAT = "openaiResponseFormat";

    private static final int DEFAULT_MAX_STEPS = 3;
    private static final int DEFAULT_FC_MAX_ROUNDS = 2;
    private static final int MAX_FC_MAX_ROUNDS = 4;
    private static final int MAX_PROMPT_STEP_OUTPUT_CHARS = 1200;
    private static final int MAX_PLANNER_RESPONSE_CHARS = 2000;

    private final ConversationService conversationService;
    private final MessageService messageService;
    private final ConversationContextService conversationContextService;
    private final AiServiceClient aiServiceClient;
    private final RequestGuardService requestGuardService;
    private final BillingService billingService;
    private final TokenEstimator tokenEstimator;
    private final OpsMetricsService opsMetricsService;
    private final AgentToolRegistry toolRegistry;
    private final AgentToolIdempotencyService agentToolIdempotencyService;
    private final AgentToolPolicyCenter agentToolPolicyCenter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentOrchestrationService(ConversationService conversationService,
                                     MessageService messageService,
                                     ConversationContextService conversationContextService,
                                     AiServiceClient aiServiceClient,
                                     RequestGuardService requestGuardService,
                                     BillingService billingService,
                                     TokenEstimator tokenEstimator,
                                     OpsMetricsService opsMetricsService,
                                     AgentToolRegistry toolRegistry,
                                     AgentToolIdempotencyService agentToolIdempotencyService,
                                     AgentToolPolicyCenter agentToolPolicyCenter) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.conversationContextService = conversationContextService;
        this.aiServiceClient = aiServiceClient;
        this.requestGuardService = requestGuardService;
        this.billingService = billingService;
        this.tokenEstimator = tokenEstimator;
        this.opsMetricsService = opsMetricsService;
        this.toolRegistry = toolRegistry;
        this.agentToolIdempotencyService = agentToolIdempotencyService;
        this.agentToolPolicyCenter = agentToolPolicyCenter;
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
            String plannerMode = resolvePlannerMode(request.metadata());
            List<String> allowedTools = resolveAllowedTools(request.metadata());
            List<AgentStepResult> stepResults;
            if (PLANNER_MODE_FUNCTION_CALLING.equals(plannerMode)) {
                stepResults = executeToolPlanByFunctionCalling(
                        userInput,
                        maxSteps,
                        conversationId,
                        request.userId(),
                        conversation.getWorkspaceId(),
                        request.provider(),
                        request.model(),
                        request.metadata(),
                        allowedTools
                );
                if (stepResults.isEmpty()) {
                    List<String> fallbackPlan = planToolsByRule(userInput, maxSteps, allowedTools);
                    stepResults = executeToolPlan(
                            fallbackPlan,
                            conversationId,
                            request.userId(),
                            conversation.getWorkspaceId(),
                            request.metadata()
                    );
                }
            } else {
                List<String> plannedTools = planTools(
                        userInput,
                        maxSteps,
                        conversationId,
                        request.userId(),
                        request.provider(),
                        request.model(),
                        plannerMode,
                        allowedTools
                );
                stepResults = executeToolPlan(
                        plannedTools,
                        conversationId,
                        request.userId(),
                        conversation.getWorkspaceId(),
                        request.metadata()
                );
            }

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
                    buildAgentMetadata(request.metadata(), stepResults, plannerMode, allowedTools)
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

    private List<AgentStepResult> executeToolPlan(List<String> plannedTools,
                                                  Long conversationId,
                                                  Long userId,
                                                  Long workspaceId,
                                                  Map<String, Object> metadata) {
        String idempotencyKey = resolveToolIdempotencyKey(metadata);
        List<AgentStepResult> result = new ArrayList<>();
        for (int i = 0; i < plannedTools.size(); i++) {
            result.add(executeToolStep(
                    i + 1,
                    plannedTools.get(i),
                    null,
                    Map.of(),
                    null,
                    conversationId,
                    userId,
                    workspaceId,
                    metadata,
                    idempotencyKey
            ));
        }
        return result;
    }

    private List<AgentStepResult> executeToolPlanByFunctionCalling(String userInput,
                                                                   int maxSteps,
                                                                   Long conversationId,
                                                                   Long userId,
                                                                   Long workspaceId,
                                                                   String provider,
                                                                   String model,
                                                                   Map<String, Object> metadata,
                                                                   List<String> allowedTools) {
        String idempotencyKey = resolveToolIdempotencyKey(metadata);
        int maxRounds = resolveFunctionCallingMaxRounds(metadata);
        int safeMaxSteps = Math.max(1, Math.min(maxSteps, 6));
        List<AgentStepResult> results = new ArrayList<>();
        String plannerInput = userInput;

        for (int round = 1; round <= maxRounds && results.size() < safeMaxSteps; round++) {
            int remainingSteps = safeMaxSteps - results.size();
            String plannerPrompt = buildFunctionCallingPlannerPrompt(plannerInput, results, remainingSteps);
            List<AiChatMessage> plannerMessages = conversationContextService.buildPromptMessages(conversationId, plannerPrompt);
            Map<String, Object> plannerMetadata = buildFunctionCallingMetadata(metadata, allowedTools, remainingSteps, round);
            AiChatRequest plannerRequest = new AiChatRequest(
                    plannerPrompt,
                    conversationId,
                    userId,
                    provider,
                    model,
                    plannerMessages,
                    plannerMetadata
            );
            AiChatResponse plannerResponse = aiServiceClient.chat(plannerRequest).block();
            if (plannerResponse == null || plannerResponse.toolCalls() == null || plannerResponse.toolCalls().isEmpty()) {
                break;
            }

            List<AiToolCall> limitedCalls = plannerResponse.toolCalls().stream()
                    .filter(call -> hasText(call.name()))
                    .filter(call -> allowedTools.contains(call.name().trim()))
                    .limit(remainingSteps)
                    .toList();
            if (limitedCalls.isEmpty()) {
                break;
            }

            List<AgentStepResult> roundSteps = executeToolCalls(
                    limitedCalls,
                    conversationId,
                    userId,
                    workspaceId,
                    metadata,
                    idempotencyKey,
                    results.size() + 1
            );
            if (roundSteps.isEmpty()) {
                break;
            }
            results.addAll(roundSteps);
            plannerInput = userInput;
        }
        return results;
    }

    private List<AgentStepResult> executeToolCalls(List<AiToolCall> toolCalls,
                                                   Long conversationId,
                                                   Long userId,
                                                   Long workspaceId,
                                                   Map<String, Object> metadata,
                                                   String idempotencyKey,
                                                   int startIndex) {
        List<AgentStepResult> results = new ArrayList<>();
        int index = startIndex;
        for (AiToolCall call : toolCalls) {
            Map<String, Object> toolArguments = parseToolArguments(call.argumentsJson());
            AgentStepResult step = executeToolStep(
                    index,
                    call.name().trim(),
                    call.id(),
                    toolArguments,
                    call.argumentsJson(),
                    conversationId,
                    userId,
                    workspaceId,
                    metadata,
                    idempotencyKey
            );
            results.add(step);
            index++;
        }
        return results;
    }

    private AgentStepResult executeToolStep(int index,
                                            String toolName,
                                            String toolCallId,
                                            Map<String, Object> toolArguments,
                                            String requestedInput,
                                            Long conversationId,
                                            Long userId,
                                            Long workspaceId,
                                            Map<String, Object> metadata,
                                            String idempotencyKey) {
        long startedAt = System.nanoTime();
        AgentTool tool = toolRegistry.get(toolName).orElse(null);
        if (tool == null) {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            return new AgentStepResult(
                    index,
                    toolName,
                    "failed",
                    safeToolInput(requestedInput),
                    truncate("tool not registered: " + toolName, MAX_PROMPT_STEP_OUTPUT_CHARS),
                    durationMs,
                    toolCallId
            );
        }

        AgentToolContext context = new AgentToolContext(
                conversationId,
                userId,
                workspaceId,
                metadata,
                toolCallId,
                toolArguments == null ? Map.of() : toolArguments
        );
        ToolExecutionPolicy policy = agentToolPolicyCenter.resolve(toolName, metadata);
        if (!policy.enabled()) {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            return new AgentStepResult(
                    index,
                    toolName,
                    "skipped",
                    safeToolInput(requestedInput),
                    "tool disabled by policy center",
                    durationMs,
                    toolCallId
            );
        }
        String toolInput = hasText(requestedInput) ? truncate(requestedInput, MAX_PROMPT_STEP_OUTPUT_CHARS) : safeBuildToolInput(tool, context);
        AgentToolIdempotencyService.Lookup lookup = new AgentToolIdempotencyService.Lookup(
                idempotencyKey,
                userId,
                workspaceId,
                conversationId,
                toolName,
                toolInput
        );
        if (hasText(idempotencyKey) && policy.idempotencyEnabled()) {
            AgentToolIdempotencyService.CachedToolResult cached = agentToolIdempotencyService.find(lookup).orElse(null);
            if (cached != null) {
                return new AgentStepResult(
                        index,
                        toolName,
                        "deduplicated",
                        hasText(cached.input()) ? cached.input() : toolInput,
                        truncate(cached.output(), MAX_PROMPT_STEP_OUTPUT_CHARS),
                        0L,
                        hasText(cached.toolCallId()) ? cached.toolCallId() : toolCallId
                );
            }
        }
        try {
            String toolOutput = executeToolWithPolicy(tool, context, policy.timeoutMs(), policy.maxRetries());
            String normalizedOutput = truncate(toolOutput, MAX_PROMPT_STEP_OUTPUT_CHARS);
            if (hasText(idempotencyKey) && policy.idempotencyEnabled()) {
                agentToolIdempotencyService.save(
                        lookup,
                        new AgentToolIdempotencyService.CachedToolResult(
                                "success",
                                toolInput,
                                normalizedOutput,
                                toolCallId
                        ),
                        policy.idempotencyTtlSeconds()
                );
            }
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            return new AgentStepResult(index, toolName, "success", toolInput, toolOutput, durationMs, toolCallId);
        } catch (RuntimeException ex) {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            String failedOutput = truncate("tool execution failed: " + ex.getMessage(), MAX_PROMPT_STEP_OUTPUT_CHARS);
            if (hasText(idempotencyKey) && policy.idempotencyEnabled()) {
                agentToolIdempotencyService.save(
                        lookup,
                        new AgentToolIdempotencyService.CachedToolResult(
                                "failed",
                                toolInput,
                                failedOutput,
                                toolCallId
                        ),
                        policy.idempotencyTtlSeconds()
                );
            }
            return new AgentStepResult(
                    index,
                    toolName,
                    "failed",
                    toolInput,
                    failedOutput,
                    durationMs,
                    toolCallId
            );
        }
    }

    private String executeToolWithPolicy(AgentTool tool,
                                         AgentToolContext context,
                                         int timeoutMs,
                                         int maxRetries) {
        Mono<String> execution = Mono.fromCallable(() -> tool.execute(context))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofMillis(timeoutMs))
                .onErrorMap(throwable -> new RuntimeException(
                        "tool " + tool.name() + " failed: " + throwable.getMessage(),
                        throwable
                ));
        if (maxRetries > 0) {
            execution = execution.retry(maxRetries);
        }
        return execution.blockOptional().orElse("");
    }

    private String safeBuildToolInput(AgentTool tool, AgentToolContext context) {
        try {
            return truncate(tool.buildInput(context), MAX_PROMPT_STEP_OUTPUT_CHARS);
        } catch (RuntimeException ex) {
            return "{}";
        }
    }

    private String safeToolInput(String requestedInput) {
        return hasText(requestedInput) ? truncate(requestedInput, MAX_PROMPT_STEP_OUTPUT_CHARS) : "{}";
    }

    private Map<String, Object> parseToolArguments(String argumentsJson) {
        if (!hasText(argumentsJson)) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(argumentsJson);
            if (!node.isObject()) {
                return Map.of();
            }
            return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String buildFunctionCallingPlannerPrompt(String userInput,
                                                     List<AgentStepResult> existingSteps,
                                                     int remainingSteps) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是 Jarvis Planner。请优先通过函数调用获取事实信息，再回答用户问题。\n");
        prompt.append("用户问题：").append(userInput).append("\n");
        prompt.append("本轮最多可再调用工具步数：").append(Math.max(1, remainingSteps)).append("\n");
        if (!existingSteps.isEmpty()) {
            prompt.append("已执行步骤：\n");
            for (AgentStepResult step : existingSteps) {
                prompt.append("- step=").append(step.index())
                        .append(", tool=").append(step.tool())
                        .append(", status=").append(step.status())
                        .append(", output=").append(truncate(step.output(), 240))
                        .append('\n');
            }
        }
        prompt.append("规则：\n");
        prompt.append("1) 需要实时信息、运营指标、会话摘要时，必须调用对应函数。\n");
        prompt.append("2) 如果信息已充分，不要重复调用同一函数。\n");
        prompt.append("3) 输出可以简短，但由系统决定是否继续调用函数。\n");
        return prompt.toString();
    }

    private Map<String, Object> buildFunctionCallingMetadata(Map<String, Object> metadata,
                                                             List<String> allowedTools,
                                                             int remainingSteps,
                                                             int round) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (metadata != null) {
            merged.putAll(metadata);
        }
        merged.put("agentPlanner", "m14-function-calling");
        merged.put("agentFcRound", round);
        merged.put("agentFcRemainingSteps", remainingSteps);
        merged.put(META_OPENAI_TOOLS, buildOpenAiToolDefinitions(allowedTools));
        merged.put(META_OPENAI_TOOL_CHOICE, "auto");
        merged.put(META_OPENAI_PARALLEL_TOOL_CALLS, false);
        merged.put(META_OPENAI_RESPONSE_FORMAT, Map.of("type", "text"));
        return merged;
    }

    private List<Map<String, Object>> buildOpenAiToolDefinitions(List<String> allowedTools) {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (String toolName : allowedTools) {
            AgentTool tool = toolRegistry.get(toolName).orElse(null);
            if (tool == null) {
                continue;
            }
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", tool.parametersSchema());
            definitions.add(Map.of(
                    "type", "function",
                    "function", function
            ));
        }
        return definitions;
    }

    private List<String> planTools(String userInput,
                                   int maxSteps,
                                   Long conversationId,
                                   Long userId,
                                   String provider,
                                   String model,
                                   String plannerMode,
                                   List<String> allowedTools) {
        if (PLANNER_MODE_LLM_JSON.equals(plannerMode)) {
            List<String> llmPlan = planToolsByLlm(userInput, maxSteps, conversationId, userId, provider, model, allowedTools);
            if (!llmPlan.isEmpty()) {
                return llmPlan;
            }
        }
        return planToolsByRule(userInput, maxSteps, allowedTools);
    }

    private List<String> planToolsByRule(String userInput, int maxSteps, List<String> allowedTools) {
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

        tools = tools.stream().filter(allowedTools::contains).toList();
        int safeMaxSteps = Math.max(1, Math.min(maxSteps, 6));
        if (tools.size() > safeMaxSteps) {
            return tools.subList(0, safeMaxSteps);
        }
        return tools;
    }

    private List<String> planToolsByLlm(String userInput,
                                        int maxSteps,
                                        Long conversationId,
                                        Long userId,
                                        String provider,
                                        String model,
                                        List<String> allowedTools) {
        try {
            String plannerPrompt = buildPlannerPrompt(userInput, allowedTools, maxSteps);
            AiChatRequest plannerRequest = new AiChatRequest(
                    plannerPrompt,
                    conversationId,
                    userId,
                    provider,
                    model,
                    null,
                    Map.of("agentPlanner", "m14-llm-json")
            );
            AiChatResponse plannerResponse = aiServiceClient.chat(plannerRequest).block();
            if (plannerResponse == null || !hasText(plannerResponse.content())) {
                return List.of();
            }
            return parsePlannerTools(plannerResponse.content(), maxSteps, allowedTools);
        } catch (RuntimeException ex) {
            return List.of();
        }
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

    private Map<String, Object> buildAgentMetadata(Map<String, Object> metadata,
                                                   List<AgentStepResult> steps,
                                                   String plannerMode,
                                                   List<String> allowedTools) {
        Map<String, Object> merged = new HashMap<>();
        if (metadata != null) {
            merged.putAll(metadata);
        }
        merged.put("agentMode", "m13-minimal");
        merged.put("agentPlannerMode", plannerMode);
        merged.put("agentAllowedTools", allowedTools);
        merged.put("agentStepCount", steps.size());
        merged.put("agentTools", steps.stream().map(AgentStepResult::tool).toList());
        merged.put("agentAuditSteps", steps.stream().map(this::toAuditStep).toList());
        return merged;
    }

    private Map<String, Object> toAuditStep(AgentStepResult step) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("index", step.index());
        audit.put("tool", step.tool());
        audit.put("status", step.status());
        audit.put("durationMs", step.durationMs());
        audit.put("input", truncate(step.input(), 300));
        audit.put("output", truncate(step.output(), 300));
        if (hasText(step.toolCallId())) {
            audit.put("toolCallId", step.toolCallId());
        }
        return audit;
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

    private String resolvePlannerMode(Map<String, Object> metadata) {
        if (metadata == null) {
            return PLANNER_MODE_RULE;
        }
        Object value = metadata.get(METADATA_PLANNER_MODE);
        if (!(value instanceof String text) || text.isBlank()) {
            return PLANNER_MODE_RULE;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (PLANNER_MODE_LLM_JSON.equals(normalized)) {
            return PLANNER_MODE_LLM_JSON;
        }
        if (PLANNER_MODE_FUNCTION_CALLING.equals(normalized)
                || "native_fc".equals(normalized)
                || "fc".equals(normalized)) {
            return PLANNER_MODE_FUNCTION_CALLING;
        }
        return PLANNER_MODE_RULE;
    }

    private List<String> resolveAllowedTools(Map<String, Object> metadata) {
        List<String> supported = supportedTools();
        if (metadata == null) {
            return supported;
        }

        Object value = metadata.get(METADATA_ALLOWED_TOOLS);
        if (!(value instanceof Collection<?> collection) || collection.isEmpty()) {
            return supported;
        }

        List<String> allowed = collection.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::trim)
                .filter(supported::contains)
                .distinct()
                .toList();
        return allowed.isEmpty() ? supported : allowed;
    }

    private List<String> supportedTools() {
        return toolRegistry.names();
    }

    private String resolveToolIdempotencyKey(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(METADATA_TOOL_IDEMPOTENCY_KEY);
        if (value instanceof String text && hasText(text)) {
            return text.trim();
        }
        return null;
    }

    private int resolveFunctionCallingMaxRounds(Map<String, Object> metadata) {
        int rounds = readPositiveInt(metadata, METADATA_FC_MAX_ROUNDS, DEFAULT_FC_MAX_ROUNDS);
        return Math.min(Math.max(rounds, 1), MAX_FC_MAX_ROUNDS);
    }

    private int readPositiveInt(Map<String, Object> metadata, String key, int defaultValue) {
        if (metadata == null || !metadata.containsKey(key)) {
            return defaultValue;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && hasText(text)) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private String buildPlannerPrompt(String userInput, List<String> allowedTools, int maxSteps) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是 Jarvis Planner。只做工具规划，不要回答最终答案。\n");
        prompt.append("用户问题：").append(userInput).append("\n");
        prompt.append("可用工具（仅可从以下选择）：\n");
        for (String tool : allowedTools) {
            prompt.append("- ").append(tool).append('\n');
        }
        prompt.append("最多工具步数：").append(Math.max(1, Math.min(maxSteps, 6))).append("\n");
        prompt.append("输出要求：仅输出 JSON，不要 markdown，不要额外解释。\n");
        prompt.append("格式：{\"tools\":[{\"name\":\"tool_name\"}]}\n");
        return prompt.toString();
    }

    private List<String> parsePlannerTools(String rawContent, int maxSteps, List<String> allowedTools) {
        String content = trimPlannerContent(rawContent);
        if (content.isBlank()) {
            return List.of();
        }

        JsonNode root = tryParseJson(content);
        if (root == null || !root.path("tools").isArray()) {
            return List.of();
        }

        List<String> planned = new ArrayList<>();
        for (JsonNode toolNode : root.path("tools")) {
            String toolName = textValue(toolNode.path("name"));
            if (!hasText(toolName)) {
                continue;
            }
            String normalized = toolName.trim();
            if (!allowedTools.contains(normalized)) {
                continue;
            }
            planned.add(normalized);
        }

        int safeMaxSteps = Math.max(1, Math.min(maxSteps, 6));
        if (planned.size() > safeMaxSteps) {
            return planned.subList(0, safeMaxSteps);
        }
        return planned;
    }

    private JsonNode tryParseJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ignored) {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return objectMapper.readTree(raw.substring(start, end + 1));
                } catch (Exception ignoredAgain) {
                    return null;
                }
            }
            return null;
        }
    }

    private String trimPlannerContent(String text) {
        String normalized = truncate(text, MAX_PLANNER_RESPONSE_CHARS);
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```[a-zA-Z]*", "");
            if (normalized.endsWith("```")) {
                normalized = normalized.substring(0, normalized.length() - 3);
            }
        }
        return normalized.trim();
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.isTextual() ? node.asText() : null;
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
            long durationMs,
            String toolCallId
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
