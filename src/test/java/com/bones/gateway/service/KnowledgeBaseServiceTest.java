package com.bones.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import com.bones.gateway.common.BusinessException;
import com.bones.gateway.common.ErrorCode;
import com.bones.gateway.dto.CreateKnowledgeSnippetRequest;
import com.bones.gateway.dto.KnowledgeSearchResponse;
import com.bones.gateway.dto.UpdateKnowledgeSnippetRequest;
import com.bones.gateway.entity.KnowledgeSnippet;
import com.bones.gateway.repository.KnowledgeSnippetRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTest {

    @Mock
    private KnowledgeSnippetRepository knowledgeSnippetRepository;
    @Mock
    private WorkspaceService workspaceService;

    private KnowledgeBaseService knowledgeBaseService;

    @BeforeEach
    void setUp() {
        knowledgeBaseService = new KnowledgeBaseService(knowledgeSnippetRepository, workspaceService);
    }

    @Test
    void createSnippet_shouldResolveWorkspaceAndPersist() {
        when(workspaceService.resolveWorkspaceId(1L, 1001L)).thenReturn(1L);
        when(knowledgeSnippetRepository.save(any(KnowledgeSnippet.class))).thenAnswer(invocation -> {
            KnowledgeSnippet snippet = invocation.getArgument(0);
            snippet.setId(10L);
            snippet.setCreatedAt(LocalDateTime.now());
            snippet.setUpdatedAt(LocalDateTime.now());
            return snippet;
        });

        var created = knowledgeBaseService.createSnippet(new CreateKnowledgeSnippetRequest(
                1001L,
                1L,
                "缓存策略",
                "本项目采用 Redis 热缓存和会话缓存。",
                List.of("cache", "redis")
        ));

        assertEquals(10L, created.id());
        assertEquals(1L, created.workspaceId());
        assertEquals("缓存策略", created.title());
        verify(workspaceService).assertCanWrite(1L, 1001L);
    }

    @Test
    void searchSnippets_shouldRankMatchedSnippetFirst() {
        when(workspaceService.resolveWorkspaceId(1L, 1001L)).thenReturn(1L);
        LocalDateTime now = LocalDateTime.now();
        when(knowledgeSnippetRepository.findTop200ByWorkspaceIdOrderByUpdatedAtDesc(eq(1L)))
                .thenReturn(List.of(
                        KnowledgeSnippet.builder()
                                .id(1L)
                                .workspaceId(1L)
                                .createdBy(1001L)
                                .title("RAG 检索链路")
                                .content("混合检索包括 BM25 与向量检索。")
                                .tags("rag,retrieval")
                                .createdAt(now.minusMinutes(2))
                                .updatedAt(now.minusMinutes(2))
                                .build(),
                        KnowledgeSnippet.builder()
                                .id(2L)
                                .workspaceId(1L)
                                .createdBy(1001L)
                                .title("缓存策略")
                                .content("Redis 会话缓存与热点缓存。")
                                .tags("cache,redis")
                                .createdAt(now.minusMinutes(1))
                                .updatedAt(now.minusMinutes(1))
                                .build()
                ));

        KnowledgeSearchResponse response = knowledgeBaseService.searchSnippets(1001L, 1L, "rag 检索", 5);

        assertEquals(1L, response.workspaceId());
        assertFalse(response.items().isEmpty());
        assertEquals("RAG 检索链路", response.items().get(0).title());
        assertEquals(5, response.limit());
    }

    @Test
    void updateSnippet_shouldCheckWritePermissionAndPersist() {
        LocalDateTime now = LocalDateTime.now();
        KnowledgeSnippet snippet = KnowledgeSnippet.builder()
                .id(5L)
                .workspaceId(1L)
                .createdBy(1001L)
                .title("旧标题")
                .content("旧内容")
                .tags("old")
                .createdAt(now.minusMinutes(5))
                .updatedAt(now.minusMinutes(5))
                .build();
        when(knowledgeSnippetRepository.findById(5L)).thenReturn(java.util.Optional.of(snippet));
        when(knowledgeSnippetRepository.save(any(KnowledgeSnippet.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var updated = knowledgeBaseService.updateSnippet(5L, new UpdateKnowledgeSnippetRequest(
                1001L,
                "新标题",
                "这是更新后的内容",
                List.of("m15", "update")
        ));

        assertEquals(5L, updated.id());
        assertEquals("新标题", updated.title());
        assertEquals("这是更新后的内容", updated.content());
        verify(workspaceService).assertCanWrite(1L, 1001L);
    }

    @Test
    void deleteSnippet_shouldDeleteWhenAuthorized() {
        KnowledgeSnippet snippet = KnowledgeSnippet.builder()
                .id(7L)
                .workspaceId(1L)
                .createdBy(1001L)
                .title("要删除")
                .content("内容")
                .build();
        when(knowledgeSnippetRepository.findById(7L)).thenReturn(java.util.Optional.of(snippet));

        knowledgeBaseService.deleteSnippet(7L, 1001L);

        verify(workspaceService).assertCanWrite(1L, 1001L);
        verify(knowledgeSnippetRepository).delete(snippet);
    }

    @Test
    void deleteSnippet_shouldThrowWhenNotFound() {
        when(knowledgeSnippetRepository.findById(999L)).thenReturn(java.util.Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.deleteSnippet(999L, 1001L));

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
        verify(workspaceService, never()).assertCanWrite(any(), any());
    }
}
