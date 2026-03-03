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
    private static final String DEFAULT_SEARCH_MODE = "hybrid";
    private static final double HYBRID_KEYWORD_WEIGHT = 0.65;
    private static final double HYBRID_VECTOR_WEIGHT = 0.35;

    private final KnowledgeSnippetRepository knowledgeSnippetRepository;
    private final WorkspaceService workspaceService;
    private final KnowledgeEmbeddingService knowledgeEmbeddingService;

    public KnowledgeBaseService(KnowledgeSnippetRepository knowledgeSnippetRepository,
                                WorkspaceService workspaceService,
                                KnowledgeEmbeddingService knowledgeEmbeddingService) {
        this.knowledgeSnippetRepository = knowledgeSnippetRepository;
        this.workspaceService = workspaceService;
        this.knowledgeEmbeddingService = knowledgeEmbeddingService;
    }

    @Transactional
    public KnowledgeSnippetItemResponse createSnippet(CreateKnowledgeSnippetRequest request) {
        Long workspaceId = workspaceService.resolveWorkspaceId(request.workspaceId(), request.userId());
        workspaceService.assertCanWrite(workspaceId, request.userId());
        String title = normalizeTitle(request.title());
        String content = normalizeContent(request.content());
        String tags = normalizeTagsForStorage(request.tags());
        KnowledgeSnippet snippet = knowledgeSnippetRepository.save(KnowledgeSnippet.builder()
                .workspaceId(workspaceId)
                .createdBy(request.userId())
                .title(title)
                .content(content)
                .tags(tags)
                .embedding(buildSerializedEmbedding(title, content, tags))
                .build());
        return toItem(snippet, null);
    }

    @Transactional
    public KnowledgeSnippetItemResponse updateSnippet(Long snippetId, UpdateKnowledgeSnippetRequest request) {
        KnowledgeSnippet snippet = loadSnippet(snippetId);
        workspaceService.assertCanWrite(snippet.getWorkspaceId(), request.userId());
        String title = normalizeTitle(request.title());
        String content = normalizeContent(request.content());
        String tags = normalizeTagsForStorage(request.tags());
        snippet.setTitle(title);
        snippet.setContent(content);
        snippet.setTags(tags);
        snippet.setEmbedding(buildSerializedEmbedding(title, content, tags));
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
        return searchSnippets(userId, workspaceId, query, limit, DEFAULT_SEARCH_MODE);
    }

    @Transactional(readOnly = true)
    public KnowledgeSearchResponse searchSnippets(Long userId,
                                                  Long workspaceId,
                                                  String query,
                                                  Integer limit,
                                                  String searchMode) {
        Long resolvedWorkspaceId = workspaceService.resolveWorkspaceId(workspaceId, userId);
        int safeLimit = resolveLimit(limit);
        String normalizedQuery = normalizeQuery(query);
        SearchMode mode = resolveSearchMode(searchMode);
        float[] queryVector = normalizedQuery.isBlank() || mode == SearchMode.KEYWORD
                ? new float[0]
                : knowledgeEmbeddingService.embed(normalizedQuery);

        List<KnowledgeSnippet> candidates = knowledgeSnippetRepository
                .findTop200ByWorkspaceIdOrderByUpdatedAtDesc(resolvedWorkspaceId);
        if (candidates.size() > MAX_SEARCH_CANDIDATES) {
            candidates = candidates.subList(0, MAX_SEARCH_CANDIDATES);
        }

        List<SnippetScore> scored = new ArrayList<>();
        for (KnowledgeSnippet snippet : candidates) {
            double keywordScore = scoreKeyword(snippet, normalizedQuery);
            double vectorScore = scoreVector(snippet, queryVector, normalizedQuery, mode);
            scored.add(new SnippetScore(snippet, keywordScore, vectorScore, 0));
        }

        double maxKeywordScore = scored.stream()
                .mapToDouble(SnippetScore::keywordScore)
                .max()
                .orElse(0);

        List<SnippetScore> ranked = new ArrayList<>();
        for (SnippetScore score : scored) {
            double finalScore = resolveFinalScore(score, maxKeywordScore, mode, normalizedQuery.isBlank());
            SnippetScore merged = new SnippetScore(
                    score.snippet(),
                    score.keywordScore(),
                    score.vectorScore(),
                    finalScore
            );
            if (shouldInclude(merged, mode, normalizedQuery.isBlank())) {
                ranked.add(merged);
            }
        }

        ranked.sort(Comparator
                .comparingDouble(SnippetScore::finalScore).reversed()
                .thenComparing(score -> score.snippet().getUpdatedAt(), Comparator.nullsLast(Comparator.reverseOrder())));

        List<KnowledgeSnippetItemResponse> items = ranked.stream()
                .limit(safeLimit)
                .map(score -> toItem(score.snippet(), normalizedQuery.isBlank() ? null : score.finalScore()))
                .toList();

        return new KnowledgeSearchResponse(resolvedWorkspaceId, normalizedQuery, mode.apiValue(), safeLimit, items);
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

    private double scoreKeyword(KnowledgeSnippet snippet, String normalizedQuery) {
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

    private double scoreVector(KnowledgeSnippet snippet,
                               float[] queryVector,
                               String normalizedQuery,
                               SearchMode searchMode) {
        if (normalizedQuery.isBlank() || searchMode == SearchMode.KEYWORD || queryVector.length == 0) {
            return 0;
        }
        float[] snippetVector = knowledgeEmbeddingService.deserialize(snippet.getEmbedding());
        if (snippetVector.length == 0) {
            snippetVector = knowledgeEmbeddingService.embed(
                    buildSnippetEmbeddingText(snippet.getTitle(), snippet.getContent(), snippet.getTags())
            );
        }
        double similarity = knowledgeEmbeddingService.cosineSimilarity(queryVector, snippetVector);
        if (Double.isNaN(similarity) || Double.isInfinite(similarity)) {
            return 0;
        }
        return Math.max(0, Math.min(1, similarity));
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

    private double resolveFinalScore(SnippetScore score,
                                     double maxKeywordScore,
                                     SearchMode searchMode,
                                     boolean blankQuery) {
        if (blankQuery) {
            return 1.0;
        }
        return switch (searchMode) {
            case KEYWORD -> score.keywordScore();
            case VECTOR -> score.vectorScore();
            case HYBRID -> {
                double keywordScore = maxKeywordScore > 0 ? score.keywordScore() / maxKeywordScore : 0;
                yield keywordScore * HYBRID_KEYWORD_WEIGHT + score.vectorScore() * HYBRID_VECTOR_WEIGHT;
            }
        };
    }

    private boolean shouldInclude(SnippetScore score, SearchMode searchMode, boolean blankQuery) {
        if (blankQuery) {
            return true;
        }
        return switch (searchMode) {
            case KEYWORD -> score.keywordScore() > 0;
            case VECTOR -> score.vectorScore() > 0;
            case HYBRID -> score.keywordScore() > 0 || score.vectorScore() > 0;
        };
    }

    private String buildSerializedEmbedding(String title, String content, String tags) {
        return knowledgeEmbeddingService.serialize(
                knowledgeEmbeddingService.embed(buildSnippetEmbeddingText(title, content, tags))
        );
    }

    private String buildSnippetEmbeddingText(String title, String content, String tags) {
        String safeTitle = title == null ? "" : title;
        String safeContent = content == null ? "" : content;
        String safeTags = tags == null ? "" : tags.replace(",", " ");
        return (safeTitle + " " + safeTitle + " " + safeTags + " " + safeContent).trim();
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

    private SearchMode resolveSearchMode(String searchMode) {
        if (searchMode == null || searchMode.isBlank()) {
            return SearchMode.HYBRID;
        }
        String normalized = searchMode.trim().toLowerCase(Locale.ROOT);
        if ("keyword".equals(normalized) || "lexical".equals(normalized)) {
            return SearchMode.KEYWORD;
        }
        if ("vector".equals(normalized) || "semantic".equals(normalized)) {
            return SearchMode.VECTOR;
        }
        return SearchMode.HYBRID;
    }

    private record SnippetScore(
            KnowledgeSnippet snippet,
            double keywordScore,
            double vectorScore,
            double finalScore
    ) {
    }

    private enum SearchMode {
        KEYWORD("keyword"),
        VECTOR("vector"),
        HYBRID("hybrid");

        private final String apiValue;

        SearchMode(String apiValue) {
            this.apiValue = apiValue;
        }

        public String apiValue() {
            return apiValue;
        }
    }
}
