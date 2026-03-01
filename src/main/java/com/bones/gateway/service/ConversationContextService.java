package com.bones.gateway.service;

import com.bones.gateway.config.ConversationContextProperties;
import com.bones.gateway.entity.Message;
import com.bones.gateway.entity.MessageRole;
import com.bones.gateway.integration.ai.model.AiChatMessage;
import com.bones.gateway.repository.MessageRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ConversationContextService {

    private static final Logger log = LoggerFactory.getLogger(ConversationContextService.class);

    private final ConversationContextProperties contextProperties;
    private final MessageRepository messageRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public ConversationContextService(ConversationContextProperties contextProperties,
                                      MessageRepository messageRepository,
                                      StringRedisTemplate stringRedisTemplate,
                                      ObjectMapper objectMapper) {
        this.contextProperties = contextProperties;
        this.messageRepository = messageRepository;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public List<AiChatMessage> buildPromptMessages(Long conversationId, String userInput) {
        List<AiChatMessage> prompt = new ArrayList<>(loadHistory(conversationId));
        prompt.add(new AiChatMessage("user", userInput));
        return trim(prompt);
    }

    public void appendMessage(Long conversationId, MessageRole role, String content) {
        if (!contextProperties.isCacheEnabled()) {
            return;
        }

        List<AiChatMessage> history = new ArrayList<>(loadHistory(conversationId));
        history.add(new AiChatMessage(toAiRole(role), content));
        persist(conversationId, trim(history));
    }

    public void rebuild(Long conversationId) {
        if (!contextProperties.isCacheEnabled()) {
            return;
        }
        persist(conversationId, loadHistoryFromDatabase(conversationId));
    }

    private List<AiChatMessage> loadHistory(Long conversationId) {
        if (!contextProperties.isCacheEnabled()) {
            return loadHistoryFromDatabase(conversationId);
        }

        String cacheKey = cacheKey(conversationId);
        String cacheValue = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cacheValue != null && !cacheValue.isBlank()) {
            try {
                return trim(objectMapper.readValue(cacheValue, new TypeReference<List<AiChatMessage>>() {
                }));
            } catch (Exception ex) {
                log.warn("failed to parse context cache for conversation {}", conversationId, ex);
            }
        }

        List<AiChatMessage> history = loadHistoryFromDatabase(conversationId);
        persist(conversationId, history);
        return history;
    }

    private List<AiChatMessage> loadHistoryFromDatabase(Long conversationId) {
        List<Message> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        List<AiChatMessage> history = messages.stream()
                .map(message -> new AiChatMessage(toAiRole(message.getRole()), message.getContent()))
                .toList();
        return trim(history);
    }

    private List<AiChatMessage> trim(List<AiChatMessage> messages) {
        int window = contextProperties.getWindowSize();
        if (messages.size() <= window) {
            return messages;
        }
        return new ArrayList<>(messages.subList(messages.size() - window, messages.size()));
    }

    private void persist(Long conversationId, List<AiChatMessage> history) {
        if (!contextProperties.isCacheEnabled()) {
            return;
        }

        try {
            String value = objectMapper.writeValueAsString(history);
            String key = cacheKey(conversationId);
            stringRedisTemplate.opsForValue().set(
                    key,
                    value,
                    Duration.ofSeconds(contextProperties.getCacheTtlSeconds())
            );
        } catch (Exception ex) {
            log.warn("failed to persist context cache for conversation {}", conversationId, ex);
        }
    }

    private String cacheKey(Long conversationId) {
        return "jarvis:ctx:" + conversationId;
    }

    private String toAiRole(MessageRole role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }
}
