package com.bones.gateway.service;

import com.bones.gateway.common.BusinessException;
import com.bones.gateway.common.ErrorCode;
import com.bones.gateway.dto.CreateKnowledgeSnippetRequest;
import com.bones.gateway.dto.KnowledgeSearchResponse;
import com.bones.gateway.dto.KnowledgeSnippetItemResponse;
import com.bones.gateway.dto.UpdateKnowledgeSnippetRequest;
import com.bones.gateway.entity.KnowledgeSnippet;
import com.bones.gateway.repository.KnowledgeSnippetRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeBaseService {

    private static final int MAX_SEARCH_CANDIDATES = 200;
    private static final int MAX_QUERY_LIMIT = 20;
    private static final int DEFAULT_SEARCH_LIMIT = 8;

    private final KnowledgeSnippetRepository knowledgeSnippetRepository;
    private final WorkspaceService workspaceService;

    public KnowledgeBaseService(KnowledgeSnippetRepository knowledgeSnippetRepository,
                                WorkspaceService workspaceService) {
        this.knowledgeSnippetRepository = knowledgeSnippetRepository;
        this.workspaceService = workspaceService;
    }

    @Transactional
    public KnowledgeSnippetItemResponse createSnippet(CreateKnowledgeSnippetRequest request) {
        Long workspaceId = workspaceService.resolveWorkspaceId(request.workspaceId(), request.userId());
        workspaceService.assertCanWrite(workspaceId, request.userId());
        KnowledgeSnippet snippet = knowledgeSnippetRepository.save(KnowledgeSnippet.builder()
                .workspaceId(workspaceId)
                .createdBy(request.userId())
                .title(normalizeTitle(request.title()))
                .content(normalizeContent(request.content()))
                .tags(normalizeTagsForStorage(request.tags()))
                .build());
        return toItem(snippet, null);
    }

    @Transactional
    public KnowledgeSnippetItemResponse updateSnippet(Long snippetId, UpdateKnowledgeSnippetRequest request) {
        KnowledgeSnippet snippet = loadSnippet(snippetId);
        workspaceService.assertCanWrite(snippet.getWorkspaceId(), request.userId());
        snippet.setTitle(normalizeTitle(request.title()));
        snippet.setContent(normalizeContent(request.content()));
        snippet.setTags(normalizeTagsForStorage(request.tags()));
        KnowledgeSnippet updated = knowledgeSnippetRepository.save(snippet);
        return toItem(updated, null);
    }

    @Transactional
    public void deleteSnippet(Long snippetId, Long userId) {
        KnowledgeSnippet snippet = loadSnippet(snippetId);
        workspaceService.assertCanWrite(snippet.getWorkspaceId(), userId);
        knowledgeSnippetRepository.delete(snippet);
    }

    @Transactional(readOnly = true)
    public KnowledgeSearchResponse searchSnippets(Long userId, Long workspaceId, String query, Integer limit) {
        Long resolvedWorkspaceId = workspaceService.resolveWorkspaceId(workspaceId, userId);
        int safeLimit = resolveLimit(limit);
        String normalizedQuery = normalizeQuery(query);

        List<KnowledgeSnippet> candidates = knowledgeSnippetRepository
                .findTop200ByWorkspaceIdOrderByUpdatedAtDesc(resolvedWorkspaceId);
        if (candidates.size() > MAX_SEARCH_CANDIDATES) {
            candidates = candidates.subList(0, MAX_SEARCH_CANDIDATES);
        }

        List<SnippetScore> ranked = new ArrayList<>();
        for (KnowledgeSnippet snippet : candidates) {
            double score = score(snippet, normalizedQuery);
            if (!normalizedQuery.isBlank() && score <= 0) {
                continue;
            }
            ranked.add(new SnippetScore(snippet, score));
        }

        ranked.sort(Comparator
                .comparingDouble(SnippetScore::score).reversed()
                .thenComparing(score -> score.snippet().getUpdatedAt(), Comparator.nullsLast(Comparator.reverseOrder())));

        List<KnowledgeSnippetItemResponse> items = ranked.stream()
                .limit(safeLimit)
                .map(score -> toItem(score.snippet(), normalizedQuery.isBlank() ? null : score.score()))
                .toList();

        return new KnowledgeSearchResponse(resolvedWorkspaceId, normalizedQuery, safeLimit, items);
    }

    private String normalizeTitle(String title) {
        String normalized = title == null ? "" : title.replaceAll("\\s+", " ").trim();
        return normalized.length() > 200 ? normalized.substring(0, 200) : normalized;
    }

    private String normalizeContent(String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return normalized.length() > 8000 ? normalized.substring(0, 8000) : normalized;
    }

    private String normalizeTagsForStorage(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            String item = tag.replaceAll("\\s+", " ").trim();
            if (item.isEmpty()) {
                continue;
            }
            if (item.length() > 24) {
                item = item.substring(0, 24);
            }
            normalized.add(item);
            if (normalized.size() >= 12) {
                break;
            }
        }
        if (normalized.isEmpty()) {
            return null;
        }
        return String.join(",", normalized);
    }

    private List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_SEARCH_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_QUERY_LIMIT));
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        return query.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private double score(KnowledgeSnippet snippet, String normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            return 1.0;
        }

        String title = normalizeForSearch(snippet.getTitle());
        String content = normalizeForSearch(snippet.getContent());
        String tags = normalizeForSearch(snippet.getTags());

        double score = 0;
        if (title.contains(normalizedQuery)) {
            score += 5;
        }
        if (tags.contains(normalizedQuery)) {
            score += 4;
        }
        if (content.contains(normalizedQuery)) {
            score += 2;
        }

        for (String token : tokenize(normalizedQuery)) {
            if (token.length() < 2) {
                continue;
            }
            if (title.contains(token)) {
                score += 2;
            }
            if (tags.contains(token)) {
                score += 1.5;
            }
            score += Math.min(3, countOccurrences(content, token)) * 0.8;
        }
        return score;
    }

    private List<String> tokenize(String query) {
        return Arrays.stream(query.split("[\\s,，。.;；:：/|]+"))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .toList();
    }

    private int countOccurrences(String text, String token) {
        if (text.isBlank() || token.isBlank()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (from >= 0) {
            int index = text.indexOf(token, from);
            if (index < 0) {
                break;
            }
            count++;
            from = index + token.length();
        }
        return count;
    }

    private String normalizeForSearch(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT);
    }

    private KnowledgeSnippetItemResponse toItem(KnowledgeSnippet snippet, Double score) {
        return new KnowledgeSnippetItemResponse(
                snippet.getId(),
                snippet.getWorkspaceId(),
                snippet.getCreatedBy(),
                snippet.getTitle(),
                snippet.getContent(),
                parseTags(snippet.getTags()),
                score,
                snippet.getCreatedAt(),
                snippet.getUpdatedAt()
        );
    }

    private KnowledgeSnippet loadSnippet(Long snippetId) {
        return knowledgeSnippetRepository.findById(snippetId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "knowledge snippet not found: " + snippetId,
                        HttpStatus.NOT_FOUND
                ));
    }

    private record SnippetScore(KnowledgeSnippet snippet, double score) {
    }
}
