package com.bones.gateway.controller;

import com.bones.gateway.common.ApiResponse;
import com.bones.gateway.common.TraceIdContext;
import com.bones.gateway.dto.AiChatRequestDto;
import com.bones.gateway.integration.ai.AiServiceClient;
import com.bones.gateway.integration.ai.model.AiChatRequest;
import com.bones.gateway.integration.ai.model.AiChatResponse;
import com.bones.gateway.security.AccessControlService;
import com.bones.gateway.service.ChatService;
import com.bones.gateway.service.RequestGuardService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/ai")
public class AiDemoController {

    private final AiServiceClient aiServiceClient;
    private final AccessControlService accessControlService;
    private final RequestGuardService requestGuardService;

    public AiDemoController(AiServiceClient aiServiceClient,
                            AccessControlService accessControlService,
                            RequestGuardService requestGuardService) {
        this.aiServiceClient = aiServiceClient;
        this.accessControlService = accessControlService;
        this.requestGuardService = requestGuardService;
    }

    @PostMapping("/chat")
    public Mono<ApiResponse<AiChatResponse>> chat(@Valid @RequestBody AiChatRequestDto request) {
        String traceId = TraceIdContext.getTraceId();
        Long userId = accessControlService.resolveUserId(request.userId());
        requestGuardService.checkAndConsume(userId, request.provider(), request.model());
        return aiServiceClient.chat(toAiRequest(request, userId))
                .map(response -> ApiResponse.success(response, traceId));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@Valid @RequestBody AiChatRequestDto request) {
        Long userId = accessControlService.resolveUserId(request.userId());
        requestGuardService.checkAndConsume(userId, request.provider(), request.model());
        return aiServiceClient.chatStream(toAiRequest(request, userId))
                .map(token -> ChatService.isReasoningToken(token)
                        ? ServerSentEvent.<String>builder().event("reasoning").data(ChatService.unwrapReasoningToken(token)).build()
                        : ServerSentEvent.<String>builder().event("delta").data(token).build())
                .onErrorResume(ex -> Flux.just(ServerSentEvent.<String>builder()
                        .event("error")
                        .data("{\"message\":\"" + sanitizeForJson(ex.getMessage()) + "\"}")
                        .build()))
                .concatWith(Flux.just(ServerSentEvent.<String>builder().event("done").data("[DONE]").build()));
    }

    private AiChatRequest toAiRequest(AiChatRequestDto request, Long userId) {
        return new AiChatRequest(
                request.message(),
                request.conversationId(),
                userId,
                request.provider(),
                request.model(),
                null,
                request.metadata()
        );
    }

    private String sanitizeForJson(String text) {
        if (text == null) {
            return "unknown stream error";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
