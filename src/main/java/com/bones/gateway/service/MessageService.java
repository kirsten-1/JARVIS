package com.bones.gateway.service;

import com.bones.gateway.dto.AppendMessageRequest;
import com.bones.gateway.dto.MessageItemResponse;
import com.bones.gateway.common.BusinessException;
import com.bones.gateway.common.ErrorCode;
import com.bones.gateway.config.M6CacheProperties;
import com.bones.gateway.entity.Conversation;
import com.bones.gateway.entity.Message;
import com.bones.gateway.repository.MessageRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MessageService {

    private static final String MESSAGE_LIST_CACHE_PREFIX = "jarvis:cache:msg:list:conv:";

    private final MessageRepository messageRepository;
    private final ConversationService conversationService;
    private final ConversationContextService conversationContextService;
    private final ReadCacheService readCacheService;
    private final M6CacheProperties cacheProperties;

    public MessageService(MessageRepository messageRepository,
                          ConversationService conversationService,
                          ConversationContextService conversationContextService,
                          ReadCacheService readCacheService,
                          M6CacheProperties cacheProperties) {
        this.messageRepository = messageRepository;
        this.conversationService = conversationService;
        this.conversationContextService = conversationContextService;
        this.readCacheService = readCacheService;
        this.cacheProperties = cacheProperties;
    }

    @Transactional(readOnly = true)
    public List<MessageItemResponse> listMessages(Long conversationId, Long userId) {
        conversationService.getUserConversation(conversationId, userId);
        String cacheKey = messageListCacheKey(conversationId, userId);
        return readCacheService.get(
                cacheKey,
                Duration.ofSeconds(cacheProperties.getMessageListTtlSeconds()),
                new TypeReference<List<MessageItemResponse>>() {
                },
                () -> messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)
                        .stream()
                        .map(this::toResponse)
                        .toList(),
                "messageList"
        );
    }

    @Transactional
    public MessageItemResponse appendMessage(Long conversationId, AppendMessageRequest request) {
        Conversation conversation = conversationService.getUserConversation(conversationId, request.userId());
        conversationService.assertCanWrite(conversation, request.userId());
        Message message = messageRepository.save(Message.builder()
                .conversationId(conversation.getId())
                .role(request.role())
                .content(request.content().trim())
                .tokenCount(request.tokenCount())
                .build());
        conversationContextService.appendMessage(conversation.getId(), request.role(), request.content().trim());
        conversationService.touchConversation(conversation);
        evictMessageListCache(conversation.getId());
        return toResponse(message);
    }

    Message saveMessage(Long conversationId, com.bones.gateway.entity.MessageRole role, String content, Integer tokenCount) {
        Message saved = messageRepository.save(Message.builder()
                .conversationId(conversationId)
                .role(role)
                .content(content)
                .tokenCount(tokenCount)
                .build());
        evictMessageListCache(conversationId);
        return saved;
    }

    @Transactional
    public Message updateMessageContent(Long messageId, String content) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.BAD_REQUEST,
                        "message not found: " + messageId,
                        HttpStatus.BAD_REQUEST
        ));
        message.setContent(content);
        Message saved = messageRepository.save(message);
        evictMessageListCache(saved.getConversationId());
        return saved;
    }

    private MessageItemResponse toResponse(Message message) {
        return new MessageItemResponse(
                message.getId(),
                message.getConversationId(),
                message.getRole(),
                message.getContent(),
                message.getTokenCount(),
                message.getCreatedAt()
        );
    }

    private String messageListCacheKey(Long conversationId, Long userId) {
        return messageListCachePrefix(conversationId) + ":user:" + userId;
    }

    private String messageListCachePrefix(Long conversationId) {
        return MESSAGE_LIST_CACHE_PREFIX + conversationId;
    }

    private void evictMessageListCache(Long conversationId) {
        readCacheService.evictByPrefix(messageListCachePrefix(conversationId), "messageList");
    }
}
