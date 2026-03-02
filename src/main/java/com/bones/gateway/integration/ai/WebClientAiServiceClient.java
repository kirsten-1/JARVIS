package com.bones.gateway.integration.ai;

import com.bones.gateway.common.BusinessException;
import com.bones.gateway.common.ErrorCode;
import com.bones.gateway.common.TraceIdContext;
import com.bones.gateway.config.AiServiceProperties;
import com.bones.gateway.config.AiServiceProperties.ProviderProperties;
import com.bones.gateway.config.AiServiceProperties.ProviderProtocol;
import com.bones.gateway.integration.ai.model.AiChatRequest;
import com.bones.gateway.integration.ai.model.AiChatResponse;
import com.bones.gateway.integration.ai.model.AiChatMessage;
import com.bones.gateway.integration.ai.model.StreamMetaTokenCodec;
import com.bones.gateway.service.OpsMetricsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Component
public class WebClientAiServiceClient implements AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(WebClientAiServiceClient.class);

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static final String METRIC_CHAT_LATENCY = "jarvis.ai.chat.latency";
    private static final String METRIC_CHAT_ERRORS = "jarvis.ai.chat.errors";
    private static final String METRIC_STREAM_LATENCY = "jarvis.ai.stream.latency";
    private static final String METRIC_STREAM_SESSIONS = "jarvis.ai.stream.sessions";
    private static final String METRIC_STREAM_ERRORS = "jarvis.ai.stream.errors";
    private static final String REASONING_TOKEN_PREFIX = "__JARVIS_REASONING__:";

    private static final String META_PROVIDER = "provider";
    private static final String META_MODEL = "model";

    private final WebClient aiWebClient;
    private final AiServiceProperties properties;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final OpsMetricsService opsMetricsService;

    public WebClientAiServiceClient(@Qualifier("aiWebClient") WebClient aiWebClient,
                                    AiServiceProperties properties,
                                    MeterRegistry meterRegistry,
                                    OpsMetricsService opsMetricsService) {
        this.aiWebClient = aiWebClient;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.opsMetricsService = opsMetricsService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Mono<AiChatResponse> chat(AiChatRequest request) {
        String traceId = resolveTraceId();
        ProviderContext primary = resolveProvider(resolveRequestedProvider(request), traceId);

        return executeChat(request, primary, traceId)
                .onErrorResume(error -> tryFallbackChat(request, primary, traceId, error));
    }

    @Override
    public Flux<String> chatStream(AiChatRequest request) {
        String traceId = resolveTraceId();
        ProviderContext primary = resolveProvider(resolveRequestedProvider(request), traceId);

        return executeChatStream(request, primary, traceId)
                .onErrorResume(error -> tryFallbackStream(request, primary, traceId, error));
    }

    private Mono<AiChatResponse> executeChat(AiChatRequest request, ProviderContext context, String traceId) {
        String requestId = resolveRequestId(request, traceId);
        Timer.Sample sample = Timer.start(meterRegistry);

        Mono<AiChatResponse> execution = switch (context.provider().getProtocol()) {
            case GEMINI -> doGeminiChat(request, context, traceId, requestId);
            case OPENAI_COMPATIBLE -> doOpenAiCompatibleChat(request, context, traceId, requestId);
            case ANTHROPIC -> doAnthropicChat(request, context, traceId, requestId);
        };

        if (shouldRetry()) {
            execution = execution.retryWhen(Retry.fixedDelay(properties.getMaxRetries(),
                            Duration.ofMillis(properties.getRetryBackoffMs()))
                    .filter(this::isRetryable));
        }

        return execution
                .doOnSuccess(resp -> sample.stop(Timer.builder(METRIC_CHAT_LATENCY)
                        .tag("provider", context.name())
                        .tag("result", "success")
                        .register(meterRegistry)))
                .doOnError(throwable -> {
                    sample.stop(Timer.builder(METRIC_CHAT_LATENCY)
                            .tag("provider", context.name())
                            .tag("result", "error")
                            .register(meterRegistry));
                    meterRegistry.counter(METRIC_CHAT_ERRORS,
                                    "provider", context.name(),
                                    "code", errorCodeOf(throwable))
                            .increment();
                });
    }

    private Flux<String> executeChatStream(AiChatRequest request, ProviderContext context, String traceId) {
        String requestId = resolveRequestId(request, traceId);
        Timer.Sample sample = Timer.start(meterRegistry);

        Flux<String> execution = switch (context.provider().getProtocol()) {
            case GEMINI -> doGeminiChatStream(request, context, traceId, requestId);
            case OPENAI_COMPATIBLE -> doOpenAiCompatibleChatStream(request, context, traceId, requestId);
            case ANTHROPIC -> doAnthropicChatStream(request, context, traceId, requestId);
        };

        if (shouldRetry()) {
            execution = execution.retryWhen(Retry.fixedDelay(properties.getMaxRetries(),
                            Duration.ofMillis(properties.getRetryBackoffMs()))
                    .filter(this::isRetryable));
        }

        Counter.builder(METRIC_STREAM_SESSIONS)
                .tag("provider", context.name())
                .tag("result", "started")
                .register(meterRegistry)
                .increment();

        return execution
                .doOnComplete(() -> {
                    sample.stop(Timer.builder(METRIC_STREAM_LATENCY)
                            .tag("provider", context.name())
                            .tag("result", "success")
                            .register(meterRegistry));
                    meterRegistry.counter(METRIC_STREAM_SESSIONS,
                                    "provider", context.name(),
                                    "result", "success")
                            .increment();
                })
                .doOnError(throwable -> {
                    sample.stop(Timer.builder(METRIC_STREAM_LATENCY)
                            .tag("provider", context.name())
                            .tag("result", "error")
                            .register(meterRegistry));
                    meterRegistry.counter(METRIC_STREAM_ERRORS,
                                    "provider", context.name(),
                                    "code", errorCodeOf(throwable))
                            .increment();
                    meterRegistry.counter(METRIC_STREAM_SESSIONS,
                                    "provider", context.name(),
                                    "result", "error")
                            .increment();
                });
    }

    private Mono<AiChatResponse> doOpenAiCompatibleChat(AiChatRequest request,
                                                         ProviderContext context,
                                                         String traceId,
                                                         String requestId) {
        ProviderProperties provider = context.provider();
        String url = buildRequestUrl(provider, provider.getChatPath(), resolveModel(provider, request));

        return aiWebClient.post()
                .uri(url)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .headers(headers -> applyHeaders(headers, provider, traceId, requestId))
                .bodyValue(buildOpenAiCompatiblePayload(request, provider, false))
                .retrieve()
                .onStatus(status -> status.isError(), clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> new BusinessException(
                                        ErrorCode.AI_SERVICE_BAD_RESPONSE,
                                        "provider " + context.name() + " returned " + clientResponse.statusCode().value() + ": " + body,
                                        HttpStatus.BAD_GATEWAY,
                                        traceId
                                )))
                .bodyToMono(JsonNode.class)
                .map(this::parseOpenAiCompatibleChatResponse)
                .onErrorMap(throwable -> mapException(throwable, traceId, context.name()));
    }

    private Flux<String> doOpenAiCompatibleChatStream(AiChatRequest request,
                                                       ProviderContext context,
                                                       String traceId,
                                                       String requestId) {
        ProviderProperties provider = context.provider();
        String url = buildRequestUrl(provider, provider.getStreamPath(), resolveModel(provider, request));

        return aiWebClient.post()
                .uri(url)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .headers(headers -> applyHeaders(headers, provider, traceId, requestId))
                .bodyValue(buildOpenAiCompatiblePayload(request, provider, true))
                .retrieve()
                .onStatus(status -> status.isError(), clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> new BusinessException(
                                        ErrorCode.AI_SERVICE_BAD_RESPONSE,
                                        "provider " + context.name() + " stream returned " + clientResponse.statusCode().value() + ": " + body,
                                        HttpStatus.BAD_GATEWAY,
                                        traceId
                                )))
                .bodyToFlux(String.class)
                .transform(this::normalizeStreamPayload)
                .flatMapIterable(this::parseOpenAiCompatibleStreamTokens)
                .filter(StringUtils::hasText)
                .onErrorMap(throwable -> mapException(throwable, traceId, context.name()));
    }

    private Mono<AiChatResponse> doGeminiChat(AiChatRequest request,
                                              ProviderContext context,
                                              String traceId,
                                              String requestId) {
        ProviderProperties provider = context.provider();
        String model = resolveModel(provider, request);
        if (!StringUtils.hasText(model)) {
            throw new BusinessException(
                    ErrorCode.AI_PROVIDER_CONFIG_INVALID,
                    "provider " + context.name() + " requires a model",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    traceId
            );
        }

        String url = buildRequestUrl(provider, provider.getChatPath(), model);

        return aiWebClient.post()
                .uri(url)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .headers(headers -> applyHeaders(headers, provider, traceId, requestId))
                .bodyValue(buildGeminiPayload(request))
                .retrieve()
                .onStatus(status -> status.isError(), clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> new BusinessException(
                                        ErrorCode.AI_SERVICE_BAD_RESPONSE,
                                        "provider " + context.name() + " returned " + clientResponse.statusCode().value() + ": " + body,
                                        HttpStatus.BAD_GATEWAY,
                                        traceId
                                )))
                .bodyToMono(JsonNode.class)
                .map(json -> parseGeminiChatResponse(json, model))
                .onErrorMap(throwable -> mapException(throwable, traceId, context.name()));
    }

    private Flux<String> doGeminiChatStream(AiChatRequest request,
                                            ProviderContext context,
                                            String traceId,
                                            String requestId) {
        ProviderProperties provider = context.provider();
        String model = resolveModel(provider, request);
        if (!StringUtils.hasText(model)) {
            throw new BusinessException(
                    ErrorCode.AI_PROVIDER_CONFIG_INVALID,
                    "provider " + context.name() + " requires a model",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    traceId
            );
        }

        String url = buildRequestUrl(provider, provider.getStreamPath(), model);

        return aiWebClient.post()
                .uri(url)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .headers(headers -> applyHeaders(headers, provider, traceId, requestId))
                .bodyValue(buildGeminiPayload(request))
                .retrieve()
                .onStatus(status -> status.isError(), clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> new BusinessException(
                                        ErrorCode.AI_SERVICE_BAD_RESPONSE,
                                        "provider " + context.name() + " stream returned " + clientResponse.statusCode().value() + ": " + body,
                                        HttpStatus.BAD_GATEWAY,
                                        traceId
                                )))
                .bodyToFlux(String.class)
                .transform(this::normalizeStreamPayload)
                .flatMapIterable(this::parseGeminiStreamTokens)
                .filter(StringUtils::hasText)
                .onErrorMap(throwable -> mapException(throwable, traceId, context.name()));
    }

    private Mono<AiChatResponse> doAnthropicChat(AiChatRequest request,
                                                 ProviderContext context,
                                                 String traceId,
                                                 String requestId) {
        ProviderProperties provider = context.provider();
        String model = resolveModel(provider, request);
        if (!StringUtils.hasText(model)) {
            throw new BusinessException(
                    ErrorCode.AI_PROVIDER_CONFIG_INVALID,
                    "provider " + context.name() + " requires a model",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    traceId
            );
        }

        String url = buildRequestUrl(provider, provider.getChatPath(), model);

        return aiWebClient.post()
                .uri(url)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .headers(headers -> applyHeaders(headers, provider, traceId, requestId))
                .bodyValue(buildAnthropicPayload(request, model, false))
                .retrieve()
                .onStatus(status -> status.isError(), clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> new BusinessException(
                                        ErrorCode.AI_SERVICE_BAD_RESPONSE,
                                        "provider " + context.name() + " returned " + clientResponse.statusCode().value() + ": " + body,
                                        HttpStatus.BAD_GATEWAY,
                                        traceId
                                )))
                .bodyToMono(JsonNode.class)
                .map(root -> parseAnthropicChatResponse(root, model))
                .onErrorMap(throwable -> mapException(throwable, traceId, context.name()));
    }

    private Flux<String> doAnthropicChatStream(AiChatRequest request,
                                               ProviderContext context,
                                               String traceId,
                                               String requestId) {
        ProviderProperties provider = context.provider();
        String model = resolveModel(provider, request);
        if (!StringUtils.hasText(model)) {
            throw new BusinessException(
                    ErrorCode.AI_PROVIDER_CONFIG_INVALID,
                    "provider " + context.name() + " requires a model",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    traceId
            );
        }

        String url = buildRequestUrl(provider, provider.getStreamPath(), model);

        return aiWebClient.post()
                .uri(url)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .headers(headers -> applyHeaders(headers, provider, traceId, requestId))
                .bodyValue(buildAnthropicPayload(request, model, true))
                .retrieve()
                .onStatus(status -> status.isError(), clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> new BusinessException(
                                        ErrorCode.AI_SERVICE_BAD_RESPONSE,
                                        "provider " + context.name() + " stream returned " + clientResponse.statusCode().value() + ": " + body,
                                        HttpStatus.BAD_GATEWAY,
                                        traceId
                                )))
                .bodyToFlux(String.class)
                .transform(this::normalizeStreamPayload)
                .flatMapIterable(this::parseAnthropicStreamTokens)
                .filter(StringUtils::hasText)
                .onErrorMap(throwable -> mapException(throwable, traceId, context.name()));
    }

    private Mono<AiChatResponse> tryFallbackChat(AiChatRequest request,
                                                  ProviderContext primary,
                                                  String traceId,
                                                  Throwable error) {
        ProviderContext fallback = resolveFallbackProvider(primary.name(), traceId, error);
        if (fallback == null) {
            return Mono.error(error);
        }
        log.warn("ai provider fallback: {} -> {}", primary.name(), fallback.name());
        opsMetricsService.recordProviderFallbackGlobal(primary.name(), fallback.name(), "sync");
        return executeChat(request, fallback, traceId);
    }

    private Flux<String> tryFallbackStream(AiChatRequest request,
                                           ProviderContext primary,
                                           String traceId,
                                           Throwable error) {
        ProviderContext fallback = resolveFallbackProvider(primary.name(), traceId, error);
        if (fallback == null) {
            return Flux.error(error);
        }
        log.warn("ai stream provider fallback: {} -> {}", primary.name(), fallback.name());
        opsMetricsService.recordProviderFallbackGlobal(primary.name(), fallback.name(), "stream");
        return executeChatStream(request, fallback, traceId);
    }

    private ProviderContext resolveFallbackProvider(String primaryProvider, String traceId, Throwable error) {
        if (!properties.isFallbackEnabled()) {
            return null;
        }
        if (!isProviderFailure(error)) {
            return null;
        }

        String fallbackProvider = normalizeProviderName(properties.getFallbackProvider());
        if (!StringUtils.hasText(fallbackProvider) || fallbackProvider.equals(primaryProvider)) {
            return null;
        }

        try {
            return resolveProvider(fallbackProvider, traceId);
        } catch (BusinessException ex) {
            log.warn("fallback provider resolution failed: {}", ex.getMessage());
            return null;
        }
    }

    private boolean isProviderFailure(Throwable throwable) {
        if (throwable instanceof BusinessException businessException) {
            ErrorCode code = businessException.getErrorCode();
            return code == ErrorCode.AI_SERVICE_TIMEOUT
                    || code == ErrorCode.AI_SERVICE_UNAVAILABLE
                    || code == ErrorCode.AI_SERVICE_BAD_RESPONSE;
        }
        return throwable instanceof TimeoutException || throwable instanceof WebClientRequestException;
    }

    private ProviderContext resolveProvider(String providerName, String traceId) {
        String requested = normalizeProviderName(providerName);
        if (!StringUtils.hasText(requested)) {
            requested = normalizeProviderName(properties.getDefaultProvider());
        }
        if (!StringUtils.hasText(requested)) {
            requested = "local";
        }
        Map<String, ProviderProperties> configuredProviders = properties.getProviders();

        if (configuredProviders == null || configuredProviders.isEmpty()) {
            String defaultProvider = normalizeProviderName(properties.getDefaultProvider());
            if (!StringUtils.hasText(defaultProvider)) {
                defaultProvider = "local";
            }
            if (StringUtils.hasText(requested)
                    && !requested.equals(defaultProvider)
                    && !requested.equals("legacy")) {
                throw new BusinessException(
                        ErrorCode.AI_PROVIDER_NOT_FOUND,
                        "ai provider not found: " + requested,
                        HttpStatus.BAD_REQUEST,
                        traceId
                );
            }
            ProviderProperties legacyProvider = properties.toLegacyProvider();
            return validateProvider(new ProviderContext(defaultProvider, legacyProvider), traceId);
        }

        for (Map.Entry<String, ProviderProperties> entry : configuredProviders.entrySet()) {
            if (normalizeProviderName(entry.getKey()).equals(requested)) {
                return validateProvider(new ProviderContext(normalizeProviderName(entry.getKey()), entry.getValue()), traceId);
            }
        }

        throw new BusinessException(
                ErrorCode.AI_PROVIDER_NOT_FOUND,
                "ai provider not found: " + requested,
                HttpStatus.BAD_REQUEST,
                traceId
        );
    }

    private ProviderContext validateProvider(ProviderContext context, String traceId) {
        ProviderProperties provider = context.provider();

        if (!provider.isEnabled()) {
            throw new BusinessException(
                    ErrorCode.AI_PROVIDER_DISABLED,
                    "ai provider disabled: " + context.name(),
                    HttpStatus.BAD_REQUEST,
                    traceId
            );
        }

        if (!StringUtils.hasText(provider.getBaseUrl())) {
            throw new BusinessException(
                    ErrorCode.AI_PROVIDER_CONFIG_INVALID,
                    "ai provider baseUrl is empty: " + context.name(),
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    traceId
            );
        }

        ProviderProtocol protocol = provider.getProtocol() == null
                ? ProviderProtocol.OPENAI_COMPATIBLE
                : provider.getProtocol();
        provider.setProtocol(protocol);

        return context;
    }

    private String resolveRequestedProvider(AiChatRequest request) {
        if (StringUtils.hasText(request.provider())) {
            return normalizeProviderName(request.provider());
        }
        if (request.metadata() != null) {
            Object provider = request.metadata().get(META_PROVIDER);
            if (provider instanceof String text && StringUtils.hasText(text)) {
                return normalizeProviderName(text);
            }
        }
        String defaultProvider = normalizeProviderName(properties.getDefaultProvider());
        return StringUtils.hasText(defaultProvider) ? defaultProvider : "local";
    }

    private String resolveModel(ProviderProperties provider, AiChatRequest request) {
        if (StringUtils.hasText(request.model())) {
            return request.model().trim();
        }
        if (request.metadata() != null) {
            Object model = request.metadata().get(META_MODEL);
            if (model instanceof String text && StringUtils.hasText(text)) {
                return text.trim();
            }
        }
        if (StringUtils.hasText(provider.getModel())) {
            return provider.getModel().trim();
        }
        return null;
    }

    private String resolveTraceId() {
        return TraceIdContext.getTraceId();
    }

    private String resolveRequestId(AiChatRequest request, String traceId) {
        if (request.metadata() != null) {
            Object requestId = request.metadata().get("requestId");
            if (requestId instanceof String text && StringUtils.hasText(text)) {
                return text;
            }
        }
        if (StringUtils.hasText(traceId)) {
            return traceId;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private Map<String, Object> buildOpenAiCompatiblePayload(AiChatRequest request,
                                                              ProviderProperties provider,
                                                              boolean stream) {
        String model = resolveModel(provider, request);

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (StringUtils.hasText(model)) {
            payload.put("model", model);
        }
        payload.put("stream", stream);
        payload.put("messages", buildOpenAiMessages(request));

        if (request.userId() != null) {
            payload.put("user", String.valueOf(request.userId()));
        }
        if (request.metadata() != null && !request.metadata().isEmpty()) {
            payload.put("metadata", request.metadata());
        }

        return payload;
    }

    private Map<String, Object> buildGeminiPayload(AiChatRequest request) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("contents", buildGeminiContents(request));
        return payload;
    }

    private Map<String, Object> buildAnthropicPayload(AiChatRequest request, String model, boolean stream) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("model", model);
        payload.put("stream", stream);
        payload.put("max_tokens", resolveAnthropicMaxTokens(request));
        payload.put("messages", buildAnthropicMessages(request));

        String systemPrompt = resolveAnthropicSystemPrompt(request);
        if (StringUtils.hasText(systemPrompt)) {
            payload.put("system", systemPrompt);
        }
        return payload;
    }

    private List<Map<String, Object>> buildOpenAiMessages(AiChatRequest request) {
        if (request.messages() != null && !request.messages().isEmpty()) {
            return request.messages().stream()
                    .filter(message -> StringUtils.hasText(message.content()))
                    .map(message -> {
                        String role = StringUtils.hasText(message.role()) ? message.role().trim() : "user";
                        return Map.<String, Object>of(
                                "role", role,
                                "content", message.content()
                        );
                    })
                    .toList();
        }

        return List.of(Map.of(
                "role", "user",
                "content", request.message()
        ));
    }

    private List<Map<String, Object>> buildGeminiContents(AiChatRequest request) {
        List<AiChatMessage> messages = request.messages();
        if (messages == null || messages.isEmpty()) {
            return List.of(Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", request.message()))
            ));
        }

        return messages.stream()
                .filter(message -> StringUtils.hasText(message.content()))
                .map(message -> Map.<String, Object>of(
                        "role", toGeminiRole(message.role()),
                        "parts", List.of(Map.of("text", message.content()))
                ))
                .toList();
    }

    private List<Map<String, Object>> buildAnthropicMessages(AiChatRequest request) {
        List<AiChatMessage> messages = request.messages();
        if (messages == null || messages.isEmpty()) {
            return List.of(Map.of(
                    "role", "user",
                    "content", List.of(Map.of("type", "text", "text", request.message()))
            ));
        }

        return messages.stream()
                .filter(message -> StringUtils.hasText(message.content()))
                .filter(message -> !"system".equalsIgnoreCase(message.role()))
                .map(message -> Map.<String, Object>of(
                        "role", toAnthropicRole(message.role()),
                        "content", List.of(Map.of("type", "text", "text", message.content()))
                ))
                .toList();
    }

    private String toGeminiRole(String role) {
        if (!StringUtils.hasText(role)) {
            return "user";
        }
        return switch (role.trim().toLowerCase(Locale.ROOT)) {
            case "assistant", "model" -> "model";
            default -> "user";
        };
    }

    private String toAnthropicRole(String role) {
        if (!StringUtils.hasText(role)) {
            return "user";
        }
        return "assistant".equalsIgnoreCase(role.trim()) ? "assistant" : "user";
    }

    private Integer resolveAnthropicMaxTokens(AiChatRequest request) {
        if (request.metadata() != null) {
            Object value = request.metadata().get("maxTokens");
            if (value instanceof Number number) {
                int max = number.intValue();
                if (max > 0) {
                    return max;
                }
            }
            if (value instanceof String text && StringUtils.hasText(text)) {
                try {
                    int max = Integer.parseInt(text.trim());
                    if (max > 0) {
                        return max;
                    }
                } catch (NumberFormatException ignored) {
                    // ignore invalid metadata value and use default
                }
            }
        }
        return 1024;
    }

    private String resolveAnthropicSystemPrompt(AiChatRequest request) {
        if (request.messages() != null) {
            for (AiChatMessage message : request.messages()) {
                if (message != null
                        && "system".equalsIgnoreCase(message.role())
                        && StringUtils.hasText(message.content())) {
                    return message.content();
                }
            }
        }
        if (request.metadata() != null) {
            Object systemPrompt = request.metadata().get("system");
            if (systemPrompt instanceof String text && StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private void applyHeaders(HttpHeaders headers,
                              ProviderProperties provider,
                              String traceId,
                              String requestId) {
        if (StringUtils.hasText(traceId)) {
            headers.set(TRACE_ID_HEADER, traceId);
        }
        headers.set(REQUEST_ID_HEADER, requestId);

        if (!provider.isApiKeyInQuery() && StringUtils.hasText(provider.getApiKey()) && StringUtils.hasText(provider.getApiKeyHeader())) {
            String prefix = provider.getApiKeyPrefix() == null ? "" : provider.getApiKeyPrefix();
            if (StringUtils.hasText(prefix)
                    && !prefix.endsWith(" ")
                    && "Authorization".equalsIgnoreCase(provider.getApiKeyHeader())) {
                prefix = prefix + " ";
            }
            String value = prefix + provider.getApiKey();
            headers.set(provider.getApiKeyHeader(), value);
        }

        if (provider.getHeaders() != null && !provider.getHeaders().isEmpty()) {
            provider.getHeaders().forEach((k, v) -> {
                if (StringUtils.hasText(k) && StringUtils.hasText(v)) {
                    headers.set(k, v);
                }
            });
        }
    }

    private String buildRequestUrl(ProviderProperties provider, String path, String model) {
        String resolvedPath = path;
        if (!StringUtils.hasText(resolvedPath)) {
            resolvedPath = provider.getProtocol() == ProviderProtocol.GEMINI
                    ? "/v1beta/models/{model}:generateContent"
                    : "/v1/chat/completions";
        }

        if (resolvedPath.contains("{model}")) {
            if (!StringUtils.hasText(model)) {
                throw new BusinessException(
                        ErrorCode.AI_PROVIDER_CONFIG_INVALID,
                        "provider path requires model placeholder but model is missing",
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
            }
            resolvedPath = resolvedPath.replace("{model}", urlEncode(model));
        }

        String baseUrl = provider.getBaseUrl().trim();
        String normalizedPath = resolvedPath.trim();

        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }

        String url = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) + normalizedPath
                : baseUrl + normalizedPath;

        if (provider.isApiKeyInQuery() && StringUtils.hasText(provider.getApiKey())) {
            String queryName = StringUtils.hasText(provider.getApiKeyQueryName())
                    ? provider.getApiKeyQueryName().trim()
                    : "key";
            String separator = url.contains("?") ? "&" : "?";
            url = url + separator + urlEncode(queryName) + "=" + urlEncode(provider.getApiKey());
        }

        return url;
    }

    private AiChatResponse parseOpenAiCompatibleChatResponse(JsonNode root) {
        String content = textValue(root.path("content"));
        String model = textValue(root.path("model"));
        String finishReason = textValue(root.path("finishReason"));
        Integer promptTokens = intValue(root.path("usage").path("prompt_tokens"));
        Integer completionTokens = intValue(root.path("usage").path("completion_tokens"));
        Integer totalTokens = intValue(root.path("usage").path("total_tokens"));
        if (totalTokens == null && promptTokens != null && completionTokens != null) {
            totalTokens = promptTokens + completionTokens;
        }

        JsonNode firstChoice = root.path("choices").isArray() && root.path("choices").size() > 0
                ? root.path("choices").get(0)
                : null;

        if (firstChoice != null) {
            finishReason = textValue(firstChoice.path("finish_reason"));
            content = textValue(firstChoice.path("message").path("content"));
            if (!StringUtils.hasText(content)) {
                content = textValue(firstChoice.path("text"));
            }
        }

        if (!StringUtils.hasText(content)) {
            content = textValue(root.path("output_text"));
        }

        return new AiChatResponse(
                content == null ? "" : content,
                model,
                finishReason,
                promptTokens,
                completionTokens,
                totalTokens
        );
    }

    private List<String> parseOpenAiCompatibleStreamTokens(String line) {
        if (!StringUtils.hasText(line) || "[DONE]".equals(line)) {
            return List.of();
        }
        if (!line.startsWith("{")) {
            return List.of(line);
        }

        try {
            JsonNode root = objectMapper.readTree(line);
            List<String> tokens = new ArrayList<>();
            String usageToken = extractOpenAiUsageToken(root);
            if (StringUtils.hasText(usageToken)) {
                tokens.add(usageToken);
            }

            JsonNode firstChoice = root.path("choices").isArray() && root.path("choices").size() > 0
                    ? root.path("choices").get(0)
                    : null;
            if (firstChoice == null) {
                String content = textValue(root.path("content"));
                if (!StringUtils.hasText(content)) {
                    content = extractTextFromNode(root.path("content"));
                }
                if (StringUtils.hasText(content)) {
                    tokens.add(content);
                }
                return tokens;
            }

            String reasoning = textValue(firstChoice.path("delta").path("reasoning_content"));
            if (!StringUtils.hasText(reasoning)) {
                reasoning = textValue(firstChoice.path("delta").path("reasoning"));
            }
            if (!StringUtils.hasText(reasoning)) {
                reasoning = extractTextFromNode(firstChoice.path("delta").path("reasoning"));
            }
            if (StringUtils.hasText(reasoning)) {
                tokens.add(REASONING_TOKEN_PREFIX + reasoning);
            }

            String content = textValue(firstChoice.path("delta").path("content"));
            if (!StringUtils.hasText(content)) {
                content = extractTextFromNode(firstChoice.path("delta").path("content"));
            }
            if (!StringUtils.hasText(content)) {
                content = textValue(firstChoice.path("message").path("content"));
            }
            if (!StringUtils.hasText(content)) {
                content = extractTextFromNode(firstChoice.path("message").path("content"));
            }
            if (!StringUtils.hasText(content)) {
                content = textValue(firstChoice.path("text"));
            }
            if (!StringUtils.hasText(content)) {
                content = textValue(root.path("content"));
            }
            if (!StringUtils.hasText(content)) {
                content = extractTextFromNode(root.path("content"));
            }
            if (StringUtils.hasText(content)) {
                tokens.add(content);
            }

            return tokens;
        } catch (Exception ex) {
            return List.of(line);
        }
    }

    private AiChatResponse parseGeminiChatResponse(JsonNode root, String model) {
        String content = extractGeminiText(root);
        String finishReason = null;
        Integer promptTokens = intValue(root.path("usageMetadata").path("promptTokenCount"));
        Integer completionTokens = intValue(root.path("usageMetadata").path("candidatesTokenCount"));
        Integer totalTokens = intValue(root.path("usageMetadata").path("totalTokenCount"));
        if (totalTokens == null && promptTokens != null && completionTokens != null) {
            totalTokens = promptTokens + completionTokens;
        }

        JsonNode firstCandidate = root.path("candidates").isArray() && root.path("candidates").size() > 0
                ? root.path("candidates").get(0)
                : null;
        if (firstCandidate != null) {
            finishReason = textValue(firstCandidate.path("finishReason"));
        }

        String modelVersion = textValue(root.path("modelVersion"));
        String modelValue = StringUtils.hasText(modelVersion) ? modelVersion : model;

        return new AiChatResponse(
                content == null ? "" : content,
                modelValue,
                finishReason,
                promptTokens,
                completionTokens,
                totalTokens
        );
    }

    private AiChatResponse parseAnthropicChatResponse(JsonNode root, String requestedModel) {
        String content = extractAnthropicText(root.path("content"));
        String finishReason = textValue(root.path("stop_reason"));
        Integer promptTokens = intValue(root.path("usage").path("input_tokens"));
        Integer completionTokens = intValue(root.path("usage").path("output_tokens"));
        Integer totalTokens = null;
        if (promptTokens != null && completionTokens != null) {
            totalTokens = promptTokens + completionTokens;
        }

        String model = textValue(root.path("model"));
        if (!StringUtils.hasText(model)) {
            model = requestedModel;
        }

        return new AiChatResponse(
                content == null ? "" : content,
                model,
                finishReason,
                promptTokens,
                completionTokens,
                totalTokens
        );
    }

    private List<String> parseGeminiStreamTokens(String tokenOrJson) {
        if (!StringUtils.hasText(tokenOrJson)) {
            return List.of();
        }
        if (!tokenOrJson.startsWith("{")) {
            return List.of(tokenOrJson);
        }

        try {
            JsonNode root = objectMapper.readTree(tokenOrJson);
            List<String> tokens = new ArrayList<>();

            String usageToken = extractGeminiUsageToken(root);
            if (StringUtils.hasText(usageToken)) {
                tokens.add(usageToken);
            }

            String content = extractGeminiText(root);
            if (StringUtils.hasText(content)) {
                tokens.add(content);
            }
            return tokens;
        } catch (Exception ex) {
            return List.of(tokenOrJson);
        }
    }

    private List<String> parseAnthropicStreamTokens(String tokenOrJson) {
        if (!StringUtils.hasText(tokenOrJson)) {
            return List.of();
        }
        if (!tokenOrJson.startsWith("{")) {
            return List.of(tokenOrJson);
        }

        try {
            JsonNode root = objectMapper.readTree(tokenOrJson);
            List<String> tokens = new ArrayList<>();

            String usageToken = extractAnthropicUsageToken(root);
            if (StringUtils.hasText(usageToken)) {
                tokens.add(usageToken);
            }

            String type = textValue(root.path("type"));
            if ("content_block_delta".equals(type)) {
                String deltaText = textValue(root.path("delta").path("text"));
                if (StringUtils.hasText(deltaText)) {
                    tokens.add(deltaText);
                }
                return tokens;
            }

            if ("message_start".equals(type)) {
                String startText = extractAnthropicText(root.path("message").path("content"));
                if (StringUtils.hasText(startText)) {
                    tokens.add(startText);
                }
                return tokens;
            }

            String content = extractAnthropicText(root.path("content"));
            if (StringUtils.hasText(content)) {
                tokens.add(content);
            }
            return tokens;
        } catch (Exception ex) {
            return List.of(tokenOrJson);
        }
    }

    private String extractGeminiText(JsonNode root) {
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            return textValue(root.path("text"));
        }

        JsonNode firstCandidate = candidates.get(0);
        JsonNode parts = firstCandidate.path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            return textValue(firstCandidate.path("output"));
        }

        List<String> texts = new ArrayList<>();
        for (JsonNode part : parts) {
            String text = textValue(part.path("text"));
            if (StringUtils.hasText(text)) {
                texts.add(text);
            }
        }

        if (texts.isEmpty()) {
            return null;
        }
        return String.join("", texts);
    }

    private String extractAnthropicText(JsonNode contentNode) {
        if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) {
            return null;
        }
        if (contentNode.isTextual()) {
            return textValue(contentNode);
        }
        if (!contentNode.isArray() || contentNode.isEmpty()) {
            return null;
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode item : contentNode) {
            String type = textValue(item.path("type"));
            if (!"text".equals(type)) {
                continue;
            }
            String text = textValue(item.path("text"));
            if (StringUtils.hasText(text)) {
                texts.add(text);
            }
        }
        return texts.isEmpty() ? null : String.join("", texts);
    }

    private Flux<String> normalizeStreamPayload(Flux<String> payload) {
        return payload
                .flatMap(chunk -> Flux.fromArray(chunk.split("\\r?\\n")))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .filter(line -> !line.startsWith(":"))
                .filter(line -> !line.startsWith("event:"))
                .map(line -> line.startsWith("data:") ? line.substring(5).trim() : line)
                .filter(StringUtils::hasText)
                .filter(line -> !line.equals("[DONE]"));
    }

    private String extractTextFromNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            List<String> texts = new ArrayList<>();
            for (JsonNode item : node) {
                String text = textValue(item.path("text"));
                if (!StringUtils.hasText(text)) {
                    text = textValue(item.path("content"));
                }
                if (!StringUtils.hasText(text)) {
                    text = textValue(item.path("value"));
                }
                if (StringUtils.hasText(text)) {
                    texts.add(text);
                }
            }
            return texts.isEmpty() ? null : String.join("", texts);
        }
        return null;
    }

    private String extractOpenAiUsageToken(JsonNode root) {
        JsonNode usageNode = root.path("usage");
        if (usageNode.isMissingNode() || usageNode.isNull()) {
            return null;
        }

        Integer promptTokens = intValue(usageNode.path("prompt_tokens"));
        Integer completionTokens = intValue(usageNode.path("completion_tokens"));
        Integer totalTokens = intValue(usageNode.path("total_tokens"));
        if (totalTokens == null && promptTokens != null && completionTokens != null) {
            totalTokens = promptTokens + completionTokens;
        }
        if (promptTokens == null && completionTokens == null && totalTokens == null) {
            return null;
        }
        return StreamMetaTokenCodec.encodeUsage(promptTokens, completionTokens, totalTokens);
    }

    private String extractGeminiUsageToken(JsonNode root) {
        JsonNode usageNode = root.path("usageMetadata");
        if (usageNode.isMissingNode() || usageNode.isNull()) {
            return null;
        }

        Integer promptTokens = intValue(usageNode.path("promptTokenCount"));
        Integer completionTokens = intValue(usageNode.path("candidatesTokenCount"));
        Integer totalTokens = intValue(usageNode.path("totalTokenCount"));
        if (totalTokens == null && promptTokens != null && completionTokens != null) {
            totalTokens = promptTokens + completionTokens;
        }
        if (promptTokens == null && completionTokens == null && totalTokens == null) {
            return null;
        }
        return StreamMetaTokenCodec.encodeUsage(promptTokens, completionTokens, totalTokens);
    }

    private String extractAnthropicUsageToken(JsonNode root) {
        JsonNode usageNode = root.path("usage");
        if (usageNode.isMissingNode() || usageNode.isNull()) {
            usageNode = root.path("message").path("usage");
        }
        if (usageNode.isMissingNode() || usageNode.isNull()) {
            return null;
        }

        Integer promptTokens = intValue(usageNode.path("input_tokens"));
        Integer completionTokens = intValue(usageNode.path("output_tokens"));
        Integer totalTokens = null;
        if (promptTokens != null && completionTokens != null) {
            totalTokens = promptTokens + completionTokens;
        }
        if (promptTokens == null && completionTokens == null && totalTokens == null) {
            return null;
        }
        return StreamMetaTokenCodec.encodeUsage(promptTokens, completionTokens, totalTokens);
    }

    private boolean isRetryable(Throwable throwable) {
        return throwable instanceof TimeoutException || throwable instanceof WebClientRequestException;
    }

    private boolean shouldRetry() {
        return properties.isRetryEnabled() && properties.getMaxRetries() > 0;
    }

    private String errorCodeOf(Throwable throwable) {
        if (throwable instanceof BusinessException be) {
            return String.valueOf(be.getErrorCode().getCode());
        }
        return "unknown";
    }

    private Throwable mapException(Throwable throwable, String traceId, String providerName) {
        if (throwable instanceof BusinessException) {
            return throwable;
        }
        if (throwable instanceof TimeoutException) {
            return new BusinessException(
                    ErrorCode.AI_SERVICE_TIMEOUT,
                    "provider " + providerName + " timeout",
                    HttpStatus.GATEWAY_TIMEOUT,
                    traceId
            );
        }
        if (throwable instanceof WebClientRequestException) {
            return new BusinessException(
                    ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "provider " + providerName + " unavailable",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    traceId
            );
        }
        return new BusinessException(
                ErrorCode.AI_SERVICE_BAD_RESPONSE,
                "provider " + providerName + " bad response: " + throwable.getMessage(),
                HttpStatus.BAD_GATEWAY,
                traceId
        );
    }

    private String normalizeProviderName(String provider) {
        if (!StringUtils.hasText(provider)) {
            return null;
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        return StringUtils.hasText(text) ? text : null;
    }

    private Integer intValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.intValue();
        }
        String text = node.asText(null);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String urlEncode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record ProviderContext(String name, ProviderProperties provider) {
    }
}
