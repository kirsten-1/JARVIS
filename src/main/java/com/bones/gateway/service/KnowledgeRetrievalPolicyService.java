package com.bones.gateway.service;

import com.bones.gateway.common.BusinessException;
import com.bones.gateway.common.ErrorCode;
import com.bones.gateway.config.KnowledgeSearchProperties;
import com.bones.gateway.dto.KnowledgeRetrievalFeedbackRequest;
import com.bones.gateway.dto.KnowledgeRetrievalFeedbackResponse;
import com.bones.gateway.dto.KnowledgeRetrievalPolicyRequest;
import com.bones.gateway.dto.KnowledgeRetrievalPolicyResponse;
import com.bones.gateway.dto.KnowledgeRetrievalRecommendationResponse;
import com.bones.gateway.entity.KnowledgeRetrievalPolicy;
import com.bones.gateway.entity.KnowledgeRetrievalPolicyMode;
import com.bones.gateway.repository.KnowledgeRetrievalPolicyRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeRetrievalPolicyService {

    private static final int DEFAULT_MIN_SAMPLES = 3;
    private static final Duration FEEDBACK_TTL = Duration.ofDays(30);

    private final StringRedisTemplate stringRedisTemplate;
    private final WorkspaceService workspaceService;
    private final KnowledgeSearchProperties knowledgeSearchProperties;
    private final KnowledgeRetrievalPolicyRepository knowledgeRetrievalPolicyRepository;

    public KnowledgeRetrievalPolicyService(StringRedisTemplate stringRedisTemplate,
                                           WorkspaceService workspaceService,
                                           KnowledgeSearchProperties knowledgeSearchProperties,
                                           KnowledgeRetrievalPolicyRepository knowledgeRetrievalPolicyRepository) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.workspaceService = workspaceService;
        this.knowledgeSearchProperties = knowledgeSearchProperties;
        this.knowledgeRetrievalPolicyRepository = knowledgeRetrievalPolicyRepository;
    }

    public KnowledgeRetrievalFeedbackResponse recordFeedback(Long userId, KnowledgeRetrievalFeedbackRequest request) {
        Long workspaceId = workspaceService.resolveWorkspaceId(request.workspaceId(), userId);
        workspaceService.assertMember(workspaceId, userId);
        LocalDate date = LocalDate.now();
        String mode = normalizeMode(request.searchMode());
        boolean helpful = Boolean.TRUE.equals(request.helpful());
        String key = feedbackKey(date, workspaceId);

        try {
            stringRedisTemplate.opsForHash().increment(key, "totalFeedback", 1);
            stringRedisTemplate.opsForHash().increment(key, modeField(mode, "total"), 1);
            if (helpful) {
                stringRedisTemplate.opsForHash().increment(key, "helpfulFeedback", 1);
                stringRedisTemplate.opsForHash().increment(key, modeField(mode, "helpful"), 1);
            }

            Double keywordWeight = clamp01OrNull(request.keywordWeight());
            Double vectorWeight = clamp01OrNull(request.vectorWeight());
            if (keywordWeight != null && vectorWeight != null) {
                String bias = vectorWeight > keywordWeight ? "vector" : "keyword";
                stringRedisTemplate.opsForHash().increment(key, "bias." + bias + ".total", 1);
                if (helpful) {
                    stringRedisTemplate.opsForHash().increment(key, "bias." + bias + ".helpful", 1);
                    stringRedisTemplate.opsForHash().increment(key, "helpful.weightSamples", 1);
                    stringRedisTemplate.opsForHash().increment(
                            key,
                            "helpful.keywordWeightMicros",
                            Math.round(keywordWeight * 1_000_000)
                    );
                    stringRedisTemplate.opsForHash().increment(
                            key,
                            "helpful.vectorWeightMicros",
                            Math.round(vectorWeight * 1_000_000)
                    );
                }
            }

            Double threshold = clamp01OrNull(request.scoreThreshold());
            if (helpful && threshold != null) {
                stringRedisTemplate.opsForHash().increment(key, "helpful.thresholdSamples", 1);
                stringRedisTemplate.opsForHash().increment(
                        key,
                        "helpful.thresholdMicros",
                        Math.round(threshold * 1_000_000)
                );
            }

            Integer maxCandidates = clampMaxCandidates(request.maxCandidates());
            if (helpful && maxCandidates != null) {
                stringRedisTemplate.opsForHash().increment(key, "helpful.maxCandidatesSamples", 1);
                stringRedisTemplate.opsForHash().increment(key, "helpful.maxCandidatesSum", maxCandidates);
            }
            stringRedisTemplate.expire(key, FEEDBACK_TTL);
        } catch (Exception ignored) {
            // feedback must not impact main flow
        }

        return new KnowledgeRetrievalFeedbackResponse(workspaceId, date, helpful, mode);
    }

    public KnowledgeRetrievalRecommendationResponse getRecommendation(Long userId,
                                                                      Long workspaceId,
                                                                      LocalDate from,
                                                                      LocalDate to,
                                                                      Integer minSamples) {
        Long resolvedWorkspaceId = workspaceService.resolveWorkspaceId(workspaceId, userId);
        workspaceService.assertMember(resolvedWorkspaceId, userId);
        LocalDate end = Objects.requireNonNullElse(to, LocalDate.now());
        LocalDate start = Objects.requireNonNullElse(from, end.minusDays(6));
        if (start.isAfter(end)) {
            start = end;
        }
        int safeMinSamples = minSamples == null ? DEFAULT_MIN_SAMPLES : Math.max(1, minSamples);
        AggregatedFeedback aggregated = aggregateFeedback(resolvedWorkspaceId, start, end);
        SuggestedPolicy policy = suggestPolicy(aggregated, safeMinSamples);
        return new KnowledgeRetrievalRecommendationResponse(
                resolvedWorkspaceId,
                start,
                end,
                safeMinSamples,
                aggregated.feedbackCount(),
                aggregated.helpfulCount(),
                ratio(aggregated.helpfulCount(), aggregated.feedbackCount()),
                policy.decision(),
                policy.keywordWeight(),
                policy.vectorWeight(),
                policy.hybridMinScore(),
                policy.maxCandidates()
        );
    }

    public KnowledgeRetrievalPolicyResponse getPolicy(Long userId, Long workspaceId) {
        Long resolvedWorkspaceId = workspaceService.resolveWorkspaceId(workspaceId, userId);
        workspaceService.assertMember(resolvedWorkspaceId, userId);
        KnowledgeRetrievalPolicy policy = knowledgeRetrievalPolicyRepository.findByWorkspaceId(resolvedWorkspaceId)
                .orElse(null);
        if (policy == null) {
            return new KnowledgeRetrievalPolicyResponse(
                    resolvedWorkspaceId,
                    KnowledgeRetrievalPolicyMode.RECOMMEND.name(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
        return toPolicyResponse(policy);
    }

    public KnowledgeRetrievalPolicyResponse upsertPolicy(Long userId, KnowledgeRetrievalPolicyRequest request) {
        Long resolvedWorkspaceId = workspaceService.resolveWorkspaceId(request.workspaceId(), userId);
        workspaceService.assertCanManage(resolvedWorkspaceId, userId);
        KnowledgeRetrievalPolicyMode mode = parsePolicyMode(request.mode());
        ManualPolicy manualPolicy = normalizeManualPolicy(
                request.keywordWeight(),
                request.vectorWeight(),
                request.hybridMinScore(),
                request.maxCandidates()
        );
        if (mode == KnowledgeRetrievalPolicyMode.MANUAL && !manualPolicy.hasAny()) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "manual mode requires at least one override value",
                    HttpStatus.BAD_REQUEST
            );
        }
        KnowledgeRetrievalPolicy policy = knowledgeRetrievalPolicyRepository.findByWorkspaceId(resolvedWorkspaceId)
                .orElseGet(() -> KnowledgeRetrievalPolicy.builder()
                        .workspaceId(resolvedWorkspaceId)
                        .build());
        policy.setMode(mode);
        policy.setKeywordWeight(manualPolicy.keywordWeight());
        policy.setVectorWeight(manualPolicy.vectorWeight());
        policy.setHybridMinScore(manualPolicy.hybridMinScore());
        policy.setMaxCandidates(manualPolicy.maxCandidates());
        policy.setUpdatedBy(userId);
        KnowledgeRetrievalPolicy saved = knowledgeRetrievalPolicyRepository.save(policy);
        return toPolicyResponse(saved);
    }

    public AutoTuneDecision resolveAutoTuneDecision(Long userId, Long workspaceId) {
        Long resolvedWorkspaceId = workspaceService.resolveWorkspaceId(workspaceId, userId);
        workspaceService.assertMember(resolvedWorkspaceId, userId);
        KnowledgeRetrievalPolicy policy = knowledgeRetrievalPolicyRepository.findByWorkspaceId(resolvedWorkspaceId)
                .orElse(null);
        KnowledgeRetrievalPolicyMode mode = policy == null ? KnowledgeRetrievalPolicyMode.RECOMMEND : policy.getMode();
        if (mode == KnowledgeRetrievalPolicyMode.OFF) {
            return new AutoTuneDecision(null, "none", mode.name(), resolvedWorkspaceId);
        }
        if (mode == KnowledgeRetrievalPolicyMode.MANUAL) {
            KnowledgeBaseService.SearchOverrides manualOverrides = toManualOverrides(policy);
            if (manualOverrides != null && manualOverrides.hasAny()) {
                return new AutoTuneDecision(
                        manualOverrides,
                        "workspace_manual_policy",
                        mode.name(),
                        resolvedWorkspaceId
                );
            }
            return new AutoTuneDecision(null, "none", mode.name(), resolvedWorkspaceId);
        }
        KnowledgeRetrievalRecommendationResponse recommendation = getRecommendation(
                userId,
                resolvedWorkspaceId,
                LocalDate.now().minusDays(6),
                LocalDate.now(),
                DEFAULT_MIN_SAMPLES
        );
        if (recommendation.feedbackCount() < recommendation.minSamples()) {
            return new AutoTuneDecision(null, "none", mode.name(), resolvedWorkspaceId);
        }
        return new AutoTuneDecision(
                new KnowledgeBaseService.SearchOverrides(
                        null,
                        recommendation.suggestedHybridMinScore(),
                        recommendation.suggestedKeywordWeight(),
                        recommendation.suggestedVectorWeight(),
                        recommendation.suggestedMaxCandidates()
                ),
                "auto_recommendation",
                mode.name(),
                resolvedWorkspaceId
        );
    }

    public KnowledgeBaseService.SearchOverrides resolveAutoTuneOverrides(Long userId, Long workspaceId) {
        return resolveAutoTuneDecision(userId, workspaceId).overrides();
    }

    private KnowledgeRetrievalPolicyResponse toPolicyResponse(KnowledgeRetrievalPolicy policy) {
        return new KnowledgeRetrievalPolicyResponse(
                policy.getWorkspaceId(),
                policy.getMode().name(),
                policy.getKeywordWeight(),
                policy.getVectorWeight(),
                policy.getHybridMinScore(),
                policy.getMaxCandidates(),
                policy.getUpdatedBy(),
                policy.getUpdatedAt()
        );
    }

    private KnowledgeRetrievalPolicyMode parsePolicyMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return KnowledgeRetrievalPolicyMode.RECOMMEND;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return KnowledgeRetrievalPolicyMode.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "invalid policy mode: " + raw + ", expected RECOMMEND/MANUAL/OFF",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private ManualPolicy normalizeManualPolicy(Double keywordWeight,
                                               Double vectorWeight,
                                               Double hybridMinScore,
                                               Integer maxCandidates) {
        Double safeKeyword = clamp01OrNull(keywordWeight);
        Double safeVector = clamp01OrNull(vectorWeight);
        Double safeHybridMinScore = clamp01OrNull(hybridMinScore);
        Integer safeMaxCandidates = clampMaxCandidates(maxCandidates);

        if (safeKeyword != null || safeVector != null) {
            if (safeKeyword == null) {
                safeKeyword = Math.max(0.0, 1.0 - safeVector);
            }
            if (safeVector == null) {
                safeVector = Math.max(0.0, 1.0 - safeKeyword);
            }
            double sum = safeKeyword + safeVector;
            if (sum <= 0.00001) {
                safeKeyword = 0.65;
                safeVector = 0.35;
            } else {
                safeKeyword = safeKeyword / sum;
                safeVector = safeVector / sum;
            }
        }
        return new ManualPolicy(safeKeyword, safeVector, safeHybridMinScore, safeMaxCandidates);
    }

    private KnowledgeBaseService.SearchOverrides toManualOverrides(KnowledgeRetrievalPolicy policy) {
        if (policy == null) {
            return null;
        }
        KnowledgeBaseService.SearchOverrides overrides = new KnowledgeBaseService.SearchOverrides(
                null,
                policy.getHybridMinScore(),
                policy.getKeywordWeight(),
                policy.getVectorWeight(),
                policy.getMaxCandidates()
        );
        return overrides.hasAny() ? overrides : null;
    }

    private AggregatedFeedback aggregateFeedback(Long workspaceId, LocalDate from, LocalDate to) {
        long feedbackCount = 0L;
        long helpfulCount = 0L;
        long vectorBiasTotal = 0L;
        long vectorBiasHelpful = 0L;
        long keywordBiasTotal = 0L;
        long keywordBiasHelpful = 0L;
        long helpfulWeightSamples = 0L;
        long helpfulKeywordMicros = 0L;
        long helpfulVectorMicros = 0L;
        long helpfulThresholdSamples = 0L;
        long helpfulThresholdMicros = 0L;
        long helpfulCandidatesSamples = 0L;
        long helpfulCandidatesSum = 0L;

        for (LocalDate date : days(from, to)) {
            String key = feedbackKey(date, workspaceId);
            Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
            if (entries == null) {
                entries = Map.of();
            }
            feedbackCount += parseLong(entries.get("totalFeedback"));
            helpfulCount += parseLong(entries.get("helpfulFeedback"));
            vectorBiasTotal += parseLong(entries.get("bias.vector.total"));
            vectorBiasHelpful += parseLong(entries.get("bias.vector.helpful"));
            keywordBiasTotal += parseLong(entries.get("bias.keyword.total"));
            keywordBiasHelpful += parseLong(entries.get("bias.keyword.helpful"));
            helpfulWeightSamples += parseLong(entries.get("helpful.weightSamples"));
            helpfulKeywordMicros += parseLong(entries.get("helpful.keywordWeightMicros"));
            helpfulVectorMicros += parseLong(entries.get("helpful.vectorWeightMicros"));
            helpfulThresholdSamples += parseLong(entries.get("helpful.thresholdSamples"));
            helpfulThresholdMicros += parseLong(entries.get("helpful.thresholdMicros"));
            helpfulCandidatesSamples += parseLong(entries.get("helpful.maxCandidatesSamples"));
            helpfulCandidatesSum += parseLong(entries.get("helpful.maxCandidatesSum"));
        }

        return new AggregatedFeedback(
                feedbackCount,
                helpfulCount,
                vectorBiasTotal,
                vectorBiasHelpful,
                keywordBiasTotal,
                keywordBiasHelpful,
                helpfulWeightSamples,
                helpfulKeywordMicros,
                helpfulVectorMicros,
                helpfulThresholdSamples,
                helpfulThresholdMicros,
                helpfulCandidatesSamples,
                helpfulCandidatesSum
        );
    }

    private SuggestedPolicy suggestPolicy(AggregatedFeedback aggregated, int minSamples) {
        double defaultKeywordWeight = clamp01(knowledgeSearchProperties.getHybridKeywordWeight(), 0.65);
        double defaultVectorWeight = clamp01(knowledgeSearchProperties.getHybridVectorWeight(), 0.35);
        double weightSum = defaultKeywordWeight + defaultVectorWeight;
        if (weightSum <= 0.00001) {
            defaultKeywordWeight = 0.65;
            defaultVectorWeight = 0.35;
        } else {
            defaultKeywordWeight /= weightSum;
            defaultVectorWeight /= weightSum;
        }

        double keywordWeight = defaultKeywordWeight;
        double vectorWeight = defaultVectorWeight;
        String decision = "default_profile";

        if (aggregated.feedbackCount() < minSamples) {
            decision = "insufficient_samples";
        } else {
            double vectorRate = ratio(aggregated.vectorBiasHelpful(), aggregated.vectorBiasTotal());
            double keywordRate = ratio(aggregated.keywordBiasHelpful(), aggregated.keywordBiasTotal());
            if (aggregated.vectorBiasTotal() > 0 && aggregated.keywordBiasTotal() > 0
                    && Math.abs(vectorRate - keywordRate) >= 0.05) {
                if (vectorRate > keywordRate) {
                    keywordWeight = 0.35;
                    vectorWeight = 0.65;
                    decision = "vector_bias_preferred";
                } else {
                    keywordWeight = 0.65;
                    vectorWeight = 0.35;
                    decision = "keyword_bias_preferred";
                }
            } else if (aggregated.helpfulWeightSamples() > 0) {
                double avgKeyword = microsToDouble(aggregated.helpfulKeywordMicros(), aggregated.helpfulWeightSamples());
                double avgVector = microsToDouble(aggregated.helpfulVectorMicros(), aggregated.helpfulWeightSamples());
                double sum = Math.max(0, avgKeyword) + Math.max(0, avgVector);
                if (sum > 0.00001) {
                    keywordWeight = Math.max(0, avgKeyword) / sum;
                    vectorWeight = Math.max(0, avgVector) / sum;
                    decision = "helpful_weight_average";
                }
            }
        }

        double hybridMinScore = clamp01(knowledgeSearchProperties.getHybridMinScore(), 0.10);
        if (aggregated.helpfulThresholdSamples() > 0) {
            hybridMinScore = clamp01(
                    microsToDouble(aggregated.helpfulThresholdMicros(), aggregated.helpfulThresholdSamples()),
                    hybridMinScore
            );
        }

        int maxCandidates = knowledgeSearchProperties.getMaxCandidates() > 0
                ? Math.min(knowledgeSearchProperties.getMaxCandidates(), 200)
                : 200;
        if (aggregated.helpfulCandidatesSamples() > 0) {
            maxCandidates = (int) Math.max(
                    1,
                    Math.min(200, aggregated.helpfulCandidatesSum() / aggregated.helpfulCandidatesSamples())
            );
        }

        return new SuggestedPolicy(keywordWeight, vectorWeight, hybridMinScore, maxCandidates, decision);
    }

    private String feedbackKey(LocalDate date, Long workspaceId) {
        return "jarvis:knowledge:feedback:" + date + ":ws:" + workspaceId;
    }

    private String normalizeMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "hybrid";
        }
        String mode = raw.trim().toLowerCase(Locale.ROOT);
        if ("keyword".equals(mode) || "vector".equals(mode) || "hybrid".equals(mode)) {
            return mode;
        }
        if ("lexical".equals(mode)) {
            return "keyword";
        }
        if ("semantic".equals(mode)) {
            return "vector";
        }
        return "hybrid";
    }

    private String modeField(String mode, String suffix) {
        return "mode." + mode + "." + suffix;
    }

    private List<LocalDate> days(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            days.add(cursor);
            cursor = cursor.plusDays(1);
        }
        return days;
    }

    private Double clamp01OrNull(Double value) {
        if (value == null) {
            return null;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private Integer clampMaxCandidates(Integer value) {
        if (value == null) {
            return null;
        }
        return Math.max(1, Math.min(200, value));
    }

    private double clamp01(double value, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private long parseLong(Object raw) {
        if (raw == null) {
            return 0L;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(raw);
        if (text.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return (double) numerator / denominator;
    }

    private double microsToDouble(long micros, long samples) {
        if (samples <= 0) {
            return 0.0;
        }
        return (double) micros / samples / 1_000_000.0;
    }

    private record AggregatedFeedback(
            long feedbackCount,
            long helpfulCount,
            long vectorBiasTotal,
            long vectorBiasHelpful,
            long keywordBiasTotal,
            long keywordBiasHelpful,
            long helpfulWeightSamples,
            long helpfulKeywordMicros,
            long helpfulVectorMicros,
            long helpfulThresholdSamples,
            long helpfulThresholdMicros,
            long helpfulCandidatesSamples,
            long helpfulCandidatesSum
    ) {
    }

    private record SuggestedPolicy(
            double keywordWeight,
            double vectorWeight,
            double hybridMinScore,
            int maxCandidates,
            String decision
    ) {
    }

    private record ManualPolicy(
            Double keywordWeight,
            Double vectorWeight,
            Double hybridMinScore,
            Integer maxCandidates
    ) {
        private boolean hasAny() {
            return keywordWeight != null
                    || vectorWeight != null
                    || hybridMinScore != null
                    || maxCandidates != null;
        }
    }

    public record AutoTuneDecision(
            KnowledgeBaseService.SearchOverrides overrides,
            String overrideSource,
            String mode,
            Long workspaceId
    ) {
    }
}
