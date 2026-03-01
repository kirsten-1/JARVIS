package com.bones.gateway.service;

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
import com.bones.gateway.integration.ai.model.StreamMetaTokenCodec;
import com.bones.gateway.service.BillingService.UsageSource;
import com.bones.gateway.service.StreamSessionService.StreamSessionSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ChatService {

    private static final String METRIC_FIRST_TOKEN_LATENCY = "jarvis.chat.stream.first_token.latency";
    public static final String REASONING_TOKEN_PREFIX = "__JARVIS_REASONING__:";
    public static final String USAGE_TOKEN_PREFIX = StreamMetaTokenCodec.USAGE_TOKEN_PREFIX;
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ConversationService conversationService;
    private final MessageService messageService;
    private final AiServiceClient aiServiceClient;
    private final RequestGuardService requestGuardService;
    private final ConversationContextService conversationContextService;
    private final BillingService billingService;
    private final TokenEstimator tokenEstimator;
    private final StreamSessionService streamSessionService;
    private final HotResponseCacheService hotResponseCacheService;
    private final MeterRegistry meterRegistry;
    private final OpsMetricsService opsMetricsService;

    public ChatService(ConversationService conversationService,
                       MessageService messageService,
                       AiServiceClient aiServiceClient,
                       RequestGuardService requestGuardService,
                       ConversationContextService conversationContextService,
                       BillingService billingService,
                       TokenEstimator tokenEstimator,
                       StreamSessionService streamSessionService,
                       HotResponseCacheService hotResponseCacheService,
                       MeterRegistry meterRegistry,
                       OpsMetricsService opsMetricsService) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.aiServiceClient = aiServiceClient;
        this.requestGuardService = requestGuardService;
        this.conversationContextService = conversationContextService;
        this.billingService = billingService;
        this.tokenEstimator = tokenEstimator;
        this.streamSessionService = streamSessionService;
        this.hotResponseCacheService = hotResponseCacheService;
        this.meterRegistry = meterRegistry;
        this.opsMetricsService = opsMetricsService;
    }

    public ConversationChatResponse chat(Long conversationId, ConversationChatRequest request) {
        Conversation conversation = getActiveConversation(conversationId, request.userId());
        requestGuardService.checkAndConsume(request.userId(), request.provider(), request.model());

        String requestedProvider = resolveProvider(request, null);
        try {
            String userInput = request.message().trim();
            List<AiChatMessage> promptMessages = conversationContextService.buildPromptMessages(conversationId, userInput);

            Message userMessage = messageService.saveMessage(
                    conversationId,
                    MessageRole.USER,
                    userInput,
                    null
            );
            conversationContextService.appendMessage(conversationId, MessageRole.USER, userInput);

            AiChatRequest aiRequest = new AiChatRequest(
                    userInput,
                    conversationId,
                    request.userId(),
                    request.provider(),
                    request.model(),
                    promptMessages,
                    request.metadata()
            );
            AiChatResponse aiResponse = hotResponseCacheService.getIfPresent(aiRequest);
            if (aiResponse == null) {
                aiResponse = aiServiceClient.chat(aiRequest).block();
                hotResponseCacheService.put(aiRequest, aiResponse);
            }

            if (aiResponse == null || !hasText(aiResponse.content())) {
                throw new BusinessException(
                        ErrorCode.AI_SERVICE_BAD_RESPONSE,
                        "ai response is empty",
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

            String resolvedProvider = resolveProvider(request, aiResponse);
            UsageDecision usageDecision = resolveUsageDecision(promptMessages, aiResponse);
            billingService.recordUsage(
                    request.userId(),
                    conversation.getWorkspaceId(),
                    resolvedProvider,
                    resolveModel(request, aiResponse),
                    usageDecision.promptTokens(),
                    usageDecision.completionTokens(),
                    usageDecision.totalTokens(),
                    usageDecision.usageSource()
            );

            conversationService.touchConversation(conversation);
            conversationService.maybeAutoGenerateTitle(conversation, userInput);
            opsMetricsService.recordSyncResult(conversation.getWorkspaceId(), resolvedProvider, true);

            return new ConversationChatResponse(
                    conversationId,
                    userMessage.getId(),
                    assistantMessage.getId(),
                    aiResponse.content(),
                    aiResponse.model(),
                    aiResponse.finishReason()
            );
        } catch (RuntimeException ex) {
            opsMetricsService.recordSyncResult(conversation.getWorkspaceId(), requestedProvider, false);
            throw ex;
        }
    }

    public ChatStreamResult chatStream(Long conversationId, ConversationChatRequest request) {
        Conversation conversation = getActiveConversation(conversationId, request.userId());
        requestGuardService.checkAndConsume(request.userId(), request.provider(), request.model());

        String userInput = request.message().trim();
        List<AiChatMessage> promptMessages = conversationContextService.buildPromptMessages(conversationId, userInput);
        AiChatRequest aiRequest = new AiChatRequest(
                userInput,
                conversationId,
                request.userId(),
                request.provider(),
                request.model(),
                promptMessages,
                request.metadata()
        );

        messageService.saveMessage(conversationId, MessageRole.USER, userInput, null);
        conversationContextService.appendMessage(conversationId, MessageRole.USER, userInput);

        Message assistantPlaceholder = messageService.saveMessage(conversationId, MessageRole.ASSISTANT, "", null);
        String streamId = streamSessionService.createSession(conversationId, request.userId(), assistantPlaceholder.getId());

        long startedAt = System.nanoTime();
        AtomicBoolean firstTokenSeen = new AtomicBoolean(false);
        StringBuilder assistantBuilder = new StringBuilder();
        AtomicReference<StreamMetaTokenCodec.Usage> streamUsage = new AtomicReference<>();
        Long workspaceId = conversation.getWorkspaceId();
        String metricProvider = resolveProvider(request, null);

        Flux<String> streamFlux = aiServiceClient.chatStream(aiRequest)
                .onErrorResume(ex -> shouldFallbackToSync(ex)
                        ? fallbackStreamBySync(aiRequest, workspaceId, request.provider(), request.model(), ex)
                        : Flux.error(ex));

        Flux<String> tokenFlux = streamFlux
                .switchIfEmpty(fallbackStreamBySync(aiRequest, workspaceId, request.provider(), request.model(), null))
                .switchIfEmpty(Flux.error(new BusinessException(
                        ErrorCode.AI_SERVICE_BAD_RESPONSE,
                        "ai stream response is empty",
                        HttpStatus.BAD_GATEWAY
                )))
                .doOnNext(token -> {
                    if (isUsageToken(token)) {
                        StreamMetaTokenCodec.Usage usage = StreamMetaTokenCodec.decodeUsage(token);
                        if (usage != null) {
                            streamUsage.set(usage);
                        }
                        return;
                    }
                    if (firstTokenSeen.compareAndSet(false, true)) {
                        long ttftMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
                        meterRegistry.timer(METRIC_FIRST_TOKEN_LATENCY)
                                .record(Duration.ofMillis(ttftMs));
                        opsMetricsService.recordTtft(workspaceId, metricProvider, ttftMs);
                    }
                    if (isReasoningToken(token)) {
                        return;
                    }
                    assistantBuilder.append(token);
                    streamSessionService.appendToken(streamId, token);
                    messageService.updateMessageContent(assistantPlaceholder.getId(), assistantBuilder.toString());
                })
                .doOnComplete(() -> {
                    String assistantContent = assistantBuilder.toString();
                    if (!assistantContent.isBlank()) {
                        conversationContextService.appendMessage(conversationId, MessageRole.ASSISTANT, assistantContent);
                        UsageDecision usageDecision = resolveStreamUsageDecision(
                                promptMessages,
                                assistantContent,
                                streamUsage.get()
                        );
                        billingService.recordUsage(
                                request.userId(),
                                workspaceId,
                                resolveProvider(request, null),
                                resolveModel(request, null),
                                usageDecision.promptTokens(),
                                usageDecision.completionTokens(),
                                usageDecision.totalTokens(),
                                usageDecision.usageSource()
                        );
                        conversationService.touchConversation(conversation);
                        conversationService.maybeAutoGenerateTitle(conversation, userInput);
                    }
                    streamSessionService.complete(streamId);
                    opsMetricsService.recordStreamResult(workspaceId, metricProvider, true);
                })
                .doOnError(ex -> {
                    streamSessionService.fail(streamId, ex.getMessage());
                    messageService.updateMessageContent(assistantPlaceholder.getId(), assistantBuilder.toString());
                    opsMetricsService.recordStreamResult(workspaceId, metricProvider, false);
                })
                .filter(token -> !isUsageToken(token));

        return new ChatStreamResult(streamId, assistantPlaceholder.getId(), tokenFlux);
    }

    private Flux<String> fallbackStreamBySync(AiChatRequest aiRequest,
                                              Long workspaceId,
                                              String provider,
                                              String model,
                                              Throwable cause) {
        opsMetricsService.recordStreamFallback(workspaceId, provider);
        if (cause != null) {
            log.warn("stream fallback to sync, provider={}, model={}, reason={}",
                    provider, model, cause.getMessage());
        } else {
            log.warn("stream fallback to sync because stream response is empty, provider={}, model={}",
                    provider, model);
        }
        Mono<AiChatResponse> fallback = aiServiceClient.chat(aiRequest);
        if (fallback == null) {
            return Flux.empty();
        }
        return fallback
                .flatMapMany(response -> {
                    if (response == null || !hasText(response.content())) {
                        return Flux.empty();
                    }
                    List<String> tokens = new ArrayList<>();
                    tokens.add(response.content());
                    if (response.promptTokens() != null || response.completionTokens() != null || response.totalTokens() != null) {
                        tokens.add(StreamMetaTokenCodec.encodeUsage(
                                response.promptTokens(),
                                response.completionTokens(),
                                response.totalTokens()
                        ));
                    }
                    return Flux.fromIterable(tokens);
                });
    }

    private boolean shouldFallbackToSync(Throwable throwable) {
        if (!(throwable instanceof BusinessException businessException)) {
            return false;
        }
        return businessException.getErrorCode() == ErrorCode.AI_SERVICE_BAD_RESPONSE;
    }

    public StreamSessionSnapshot getStreamSnapshot(String streamId, Long userId) {
        StreamSessionSnapshot snapshot = streamSessionService.getSnapshot(streamId);
        if (snapshot == null) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "stream session not found: " + streamId,
                    HttpStatus.BAD_REQUEST
            );
        }

        conversationService.getUserConversation(snapshot.conversationId(), userId);
        return snapshot;
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

    public static boolean isReasoningToken(String token) {
        return token != null && token.startsWith(REASONING_TOKEN_PREFIX);
    }

    public static String unwrapReasoningToken(String token) {
        if (!isReasoningToken(token)) {
            return token;
        }
        return token.substring(REASONING_TOKEN_PREFIX.length());
    }

    public static boolean isUsageToken(String token) {
        return StreamMetaTokenCodec.isUsageToken(token);
    }

    private UsageDecision resolveStreamUsageDecision(List<AiChatMessage> promptMessages,
                                                     String assistantContent,
                                                     StreamMetaTokenCodec.Usage streamUsage) {
        int estimatedPrompt = tokenEstimator.estimateMessagesTokens(promptMessages);
        int estimatedCompletion = tokenEstimator.estimateTextTokens(assistantContent);

        if (streamUsage == null) {
            return new UsageDecision(
                    estimatedPrompt,
                    estimatedCompletion,
                    estimatedPrompt + estimatedCompletion,
                    UsageSource.ESTIMATED
            );
        }

        Integer actualPrompt = streamUsage.promptTokens();
        Integer actualCompletion = streamUsage.completionTokens();
        Integer actualTotal = streamUsage.totalTokens();

        if (actualTotal == null && actualPrompt != null && actualCompletion != null) {
            actualTotal = actualPrompt + actualCompletion;
        }
        if (actualPrompt == null && actualTotal != null && actualCompletion != null) {
            actualPrompt = Math.max(0, actualTotal - actualCompletion);
        }
        if (actualCompletion == null && actualTotal != null && actualPrompt != null) {
            actualCompletion = Math.max(0, actualTotal - actualPrompt);
        }
        if (actualPrompt != null && actualCompletion != null) {
            int total = actualTotal == null ? actualPrompt + actualCompletion : actualTotal;
            return new UsageDecision(actualPrompt, actualCompletion, total, UsageSource.ACTUAL);
        }

        return new UsageDecision(
                estimatedPrompt,
                estimatedCompletion,
                estimatedPrompt + estimatedCompletion,
                UsageSource.ESTIMATED
        );
    }

    public record ChatStreamResult(
            String streamId,
            Long assistantMessageId,
            Flux<String> tokenFlux
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
