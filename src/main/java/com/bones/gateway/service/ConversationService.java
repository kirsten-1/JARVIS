package com.bones.gateway.service;

import com.bones.gateway.common.BusinessException;
import com.bones.gateway.common.ErrorCode;
import com.bones.gateway.config.M6CacheProperties;
import com.bones.gateway.dto.ConversationItemResponse;
import com.bones.gateway.dto.CreateConversationRequest;
import com.bones.gateway.dto.PagedResult;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import com.bones.gateway.entity.Conversation;
import com.bones.gateway.entity.ConversationStatus;
import com.bones.gateway.repository.ConversationRepository;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {

    private static final String DEFAULT_TITLE = "新会话";
    private static final int MAX_PAGE_SIZE = 100;
    private static final String CONVERSATION_LIST_CACHE_PREFIX = "jarvis:cache:conv:list:workspace:";

    private final ConversationRepository conversationRepository;
    private final WorkspaceService workspaceService;
    private final ReadCacheService readCacheService;
    private final M6CacheProperties cacheProperties;

    public ConversationService(ConversationRepository conversationRepository,
                               WorkspaceService workspaceService,
                               ReadCacheService readCacheService,
                               M6CacheProperties cacheProperties) {
        this.conversationRepository = conversationRepository;
        this.workspaceService = workspaceService;
        this.readCacheService = readCacheService;
        this.cacheProperties = cacheProperties;
    }

    @Transactional
    public ConversationItemResponse createConversation(CreateConversationRequest request) {
        Long workspaceId = workspaceService.resolveWorkspaceId(request.workspaceId(), request.userId());
        Conversation conversation = Conversation.builder()
                .userId(request.userId())
                .workspaceId(workspaceId)
                .title(resolveTitle(request.title()))
                .status(ConversationStatus.ACTIVE)
                .build();

        ConversationItemResponse created = toResponse(conversationRepository.save(conversation));
        evictConversationListCache(created.workspaceId());
        return created;
    }

    @Transactional(readOnly = true)
    public PagedResult<ConversationItemResponse> listConversations(Long userId, Long workspaceId, int page, int size) {
        Long resolvedWorkspaceId = workspaceService.resolveWorkspaceId(workspaceId, userId);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String cacheKey = conversationListCacheKey(resolvedWorkspaceId, safePage, safeSize);

        return readCacheService.get(
                cacheKey,
                Duration.ofSeconds(cacheProperties.getConversationListTtlSeconds()),
                new TypeReference<PagedResult<ConversationItemResponse>>() {
                },
                () -> {
                    Page<Conversation> result = conversationRepository
                            .findAllByWorkspaceIdOrderByUpdatedAtDesc(resolvedWorkspaceId, PageRequest.of(safePage, safeSize));
                    return new PagedResult<>(
                            result.getContent().stream().map(this::toResponse).toList(),
                            result.getNumber(),
                            result.getSize(),
                            result.getTotalElements(),
                            result.getTotalPages()
                    );
                },
                "conversationList"
        );
    }

    @Transactional
    public ConversationItemResponse archiveConversation(Long conversationId, Long userId) {
        Conversation conversation = getUserConversation(conversationId, userId);
        assertCanManage(conversation, userId);
        conversation.setStatus(ConversationStatus.ARCHIVED);
        ConversationItemResponse archived = toResponse(conversationRepository.save(conversation));
        evictConversationListCache(archived.workspaceId());
        return archived;
    }

    @Transactional(readOnly = true)
    public Conversation getUserConversation(Long conversationId, Long userId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.CONVERSATION_NOT_FOUND,
                        "conversation not found: " + conversationId,
                        HttpStatus.NOT_FOUND
                ));

        // Compatibility path for legacy data before workspace_id backfill.
        if (conversation.getWorkspaceId() == null) {
            if (!Objects.equals(conversation.getUserId(), userId)) {
                throw new BusinessException(
                        ErrorCode.FORBIDDEN,
                        ErrorCode.FORBIDDEN.getMessage(),
                        HttpStatus.FORBIDDEN
                );
            }
            return conversation;
        }

        workspaceService.assertMember(conversation.getWorkspaceId(), userId);
        return conversation;
    }

    @Transactional(readOnly = true)
    public void assertCanWrite(Conversation conversation, Long userId) {
        if (conversation == null) {
            return;
        }
        if (conversation.getWorkspaceId() == null) {
            if (!Objects.equals(conversation.getUserId(), userId)) {
                throw new BusinessException(
                        ErrorCode.FORBIDDEN,
                        ErrorCode.FORBIDDEN.getMessage(),
                        HttpStatus.FORBIDDEN
                );
            }
            return;
        }
        workspaceService.assertCanWrite(conversation.getWorkspaceId(), userId);
    }

    @Transactional(readOnly = true)
    public void assertCanManage(Conversation conversation, Long userId) {
        if (conversation == null) {
            return;
        }
        if (conversation.getWorkspaceId() == null) {
            if (!Objects.equals(conversation.getUserId(), userId)) {
                throw new BusinessException(
                        ErrorCode.FORBIDDEN,
                        ErrorCode.FORBIDDEN.getMessage(),
                        HttpStatus.FORBIDDEN
                );
            }
            return;
        }
        workspaceService.assertCanManage(conversation.getWorkspaceId(), userId);
    }

    @Transactional
    public void touchConversation(Conversation conversation) {
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);
        evictConversationListCache(conversation.getWorkspaceId());
    }

    @Transactional
    public void maybeAutoGenerateTitle(Conversation conversation, String userInput) {
        if (conversation == null || userInput == null || userInput.isBlank()) {
            return;
        }
        if (!DEFAULT_TITLE.equals(conversation.getTitle())) {
            return;
        }
        String generatedTitle = generateTitle(userInput);
        conversation.setTitle(generatedTitle);
        conversationRepository.save(conversation);
        evictConversationListCache(conversation.getWorkspaceId());
    }

    private ConversationItemResponse toResponse(Conversation conversation) {
        return new ConversationItemResponse(
                conversation.getId(),
                conversation.getUserId(),
                conversation.getWorkspaceId(),
                conversation.getTitle(),
                conversation.getStatus(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }

    private String resolveTitle(String title) {
        if (title == null || title.isBlank()) {
            return DEFAULT_TITLE;
        }
        return title.trim();
    }

    private String generateTitle(String userInput) {
        String normalized = userInput.replaceAll("\\s+", " ").trim();
        int limit = 24;
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, limit) + "...";
    }

    private String conversationListCacheKey(Long workspaceId, int page, int size) {
        return conversationListCachePrefix(workspaceId) + ":p:" + page + ":s:" + size;
    }

    private String conversationListCachePrefix(Long workspaceId) {
        return CONVERSATION_LIST_CACHE_PREFIX + workspaceId;
    }

    private void evictConversationListCache(Long workspaceId) {
        if (workspaceId == null) {
            return;
        }
        readCacheService.evictByPrefix(conversationListCachePrefix(workspaceId), "conversationList");
    }
}
