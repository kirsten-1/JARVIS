package com.bones.gateway.controller;

import com.bones.gateway.common.ApiResponse;
import com.bones.gateway.dto.AgentRunRequest;
import com.bones.gateway.dto.AgentRunResponse;
import com.bones.gateway.dto.AppendMessageRequest;
import com.bones.gateway.dto.ConversationChatRequest;
import com.bones.gateway.dto.ConversationChatResponse;
import com.bones.gateway.dto.ConversationItemResponse;
import com.bones.gateway.dto.CreateConversationRequest;
import com.bones.gateway.dto.MessageItemResponse;
import com.bones.gateway.dto.PagedResult;
import com.bones.gateway.dto.StreamSessionSnapshotResponse;
import com.bones.gateway.security.AccessControlService;
import com.bones.gateway.service.AgentOrchestrationService;
import com.bones.gateway.service.ChatService;
import com.bones.gateway.service.ConversationService;
import com.bones.gateway.service.MessageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@Validated
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final MessageService messageService;
    private final ChatService chatService;
    private final AgentOrchestrationService agentOrchestrationService;
    private final AccessControlService accessControlService;

    public ConversationController(ConversationService conversationService,
                                  MessageService messageService,
                                  ChatService chatService,
                                  AgentOrchestrationService agentOrchestrationService,
                                  AccessControlService accessControlService) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.chatService = chatService;
        this.agentOrchestrationService = agentOrchestrationService;
        this.accessControlService = accessControlService;
    }

    @PostMapping
    public ApiResponse<ConversationItemResponse> createConversation(@Valid @RequestBody CreateConversationRequest request) {
        Long userId = accessControlService.resolveUserId(request.userId());
        return ApiResponse.success(conversationService.createConversation(
                new CreateConversationRequest(userId, request.workspaceId(), request.title())
        ));
    }

    @GetMapping
    public ApiResponse<PagedResult<ConversationItemResponse>> listConversations(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "workspaceId", required = false) Long workspaceId,
            @RequestParam(value = "page", defaultValue = "0") @Min(value = 0, message = "page must be >= 0") int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(value = 1, message = "size must be >= 1") int size) {
        Long targetUserId = accessControlService.resolveUserId(userId);
        return ApiResponse.success(conversationService.listConversations(targetUserId, workspaceId, page, size));
    }

    @PostMapping("/{conversationId}/archive")
    public ApiResponse<ConversationItemResponse> archiveConversation(
            @PathVariable("conversationId") Long conversationId,
            @RequestParam(value = "userId", required = false) Long userId) {
        Long targetUserId = accessControlService.resolveUserId(userId);
        return ApiResponse.success(conversationService.archiveConversation(conversationId, targetUserId));
    }

    @GetMapping("/{conversationId}/messages")
    public ApiResponse<List<MessageItemResponse>> listMessages(
            @PathVariable("conversationId") Long conversationId,
            @RequestParam(value = "userId", required = false) Long userId) {
        Long targetUserId = accessControlService.resolveUserId(userId);
        return ApiResponse.success(messageService.listMessages(conversationId, targetUserId));
    }

    @PostMapping("/{conversationId}/messages")
    public ApiResponse<MessageItemResponse> appendMessage(
            @PathVariable("conversationId") Long conversationId,
            @Valid @RequestBody AppendMessageRequest request) {
        Long userId = accessControlService.resolveUserId(request.userId());
        return ApiResponse.success(messageService.appendMessage(conversationId, new AppendMessageRequest(
                userId,
                request.role(),
                request.content(),
                request.tokenCount()
        )));
    }

    @PostMapping("/{conversationId}/chat")
    public ApiResponse<ConversationChatResponse> chat(
            @PathVariable("conversationId") Long conversationId,
            @Valid @RequestBody ConversationChatRequest request) {
        Long userId = accessControlService.resolveUserId(request.userId());
        return ApiResponse.success(chatService.chat(conversationId, new ConversationChatRequest(
                userId,
                request.message(),
                request.provider(),
                request.model(),
                request.metadata()
        )));
    }

    @PostMapping("/{conversationId}/agent/run")
    public ApiResponse<AgentRunResponse> runAgent(
            @PathVariable("conversationId") Long conversationId,
            @Valid @RequestBody AgentRunRequest request) {
        Long userId = accessControlService.resolveUserId(request.userId());
        return ApiResponse.success(agentOrchestrationService.run(conversationId, new AgentRunRequest(
                userId,
                request.message(),
                request.provider(),
                request.model(),
                request.maxSteps(),
                request.metadata()
        )));
    }

    @PostMapping(value = "/{conversationId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @PathVariable("conversationId") Long conversationId,
            @Valid @RequestBody ConversationChatRequest request) {
        Long userId = accessControlService.resolveUserId(request.userId());
        ChatService.ChatStreamResult streamResult = chatService.chatStream(conversationId, new ConversationChatRequest(
                userId,
                request.message(),
                request.provider(),
                request.model(),
                request.metadata()
        ));

        String metaJson = "{\"streamId\":\"" + streamResult.streamId() + "\",\"assistantMessageId\":" + streamResult.assistantMessageId() + "}";
        Flux<ServerSentEvent<String>> tokenEvents = streamResult.tokenFlux()
                .map(token -> ChatService.isReasoningToken(token)
                        ? ServerSentEvent.<String>builder().event("reasoning").data(ChatService.unwrapReasoningToken(token)).build()
                        : ServerSentEvent.<String>builder().event("delta").data(token).build())
                .onErrorResume(ex -> Flux.just(ServerSentEvent.<String>builder()
                        .event("error")
                        .data("{\"message\":\"" + sanitizeForJson(ex.getMessage()) + "\"}")
                        .build()));

        return Flux.just(ServerSentEvent.<String>builder().event("meta").data(metaJson).build())
                .concatWith(tokenEvents)
                .concatWith(Flux.just(ServerSentEvent.<String>builder().event("done").data("[DONE]").build()));
    }

    @GetMapping("/streams/{streamId}")
    public ApiResponse<StreamSessionSnapshotResponse> getStreamSnapshot(
            @PathVariable("streamId") String streamId,
            @RequestParam(value = "userId", required = false) Long userId) {
        Long targetUserId = accessControlService.resolveUserId(userId);
        var snapshot = chatService.getStreamSnapshot(streamId, targetUserId);
        return ApiResponse.success(new StreamSessionSnapshotResponse(
                snapshot.streamId(),
                snapshot.conversationId(),
                snapshot.assistantMessageId(),
                snapshot.status(),
                snapshot.content(),
                snapshot.updatedAt(),
                snapshot.error()
        ));
    }

    private String sanitizeForJson(String text) {
        if (text == null) {
            return "unknown stream error";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
