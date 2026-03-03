package com.bones.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bones.gateway.config.KnowledgeSearchProperties;
import com.bones.gateway.dto.KnowledgeRetrievalRecommendationResponse;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class KnowledgeRetrievalPolicyServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private KnowledgeRetrievalPolicyService knowledgeRetrievalPolicyService;

    @BeforeEach
    void setUp() {
        KnowledgeSearchProperties properties = new KnowledgeSearchProperties();
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(anyString())).thenReturn(Map.of());
        knowledgeRetrievalPolicyService = new KnowledgeRetrievalPolicyService(
                stringRedisTemplate,
                workspaceService,
                properties
        );
    }

    @Test
    void getRecommendation_shouldPreferVectorBiasWhenHelpfulRateHigher() {
        LocalDate today = LocalDate.now();
        String key = "jarvis:knowledge:feedback:" + today + ":ws:1";
        when(workspaceService.resolveWorkspaceId(1L, 1001L)).thenReturn(1L);
        when(hashOperations.entries(key)).thenReturn(Map.ofEntries(
                Map.entry("totalFeedback", "4"),
                Map.entry("helpfulFeedback", "3"),
                Map.entry("bias.vector.total", "3"),
                Map.entry("bias.vector.helpful", "3"),
                Map.entry("bias.keyword.total", "1"),
                Map.entry("bias.keyword.helpful", "0"),
                Map.entry("helpful.weightSamples", "3"),
                Map.entry("helpful.keywordWeightMicros", "750000"),
                Map.entry("helpful.vectorWeightMicros", "2250000"),
                Map.entry("helpful.thresholdSamples", "3"),
                Map.entry("helpful.thresholdMicros", "660000"),
                Map.entry("helpful.maxCandidatesSamples", "3"),
                Map.entry("helpful.maxCandidatesSum", "180")
        ));

        KnowledgeRetrievalRecommendationResponse response = knowledgeRetrievalPolicyService.getRecommendation(
                1001L,
                1L,
                today,
                today,
                3
        );

        assertEquals(4L, response.feedbackCount());
        assertEquals("vector_bias_preferred", response.decision());
        assertTrue(response.suggestedVectorWeight() > response.suggestedKeywordWeight());
        assertEquals(0.22, response.suggestedHybridMinScore(), 0.0001);
        assertEquals(60, response.suggestedMaxCandidates());
        verify(workspaceService).assertMember(1L, 1001L);
    }

    @Test
    void resolveAutoTuneOverrides_shouldReturnNullWhenSamplesInsufficient() {
        LocalDate today = LocalDate.now();
        String key = "jarvis:knowledge:feedback:" + today + ":ws:1";
        when(workspaceService.resolveWorkspaceId(1L, 1001L)).thenReturn(1L);
        when(hashOperations.entries(key)).thenReturn(Map.of(
                "totalFeedback", "2",
                "helpfulFeedback", "1"
        ));

        KnowledgeBaseService.SearchOverrides overrides = knowledgeRetrievalPolicyService.resolveAutoTuneOverrides(1001L, 1L);

        assertNull(overrides);
    }

    @Test
    void resolveAutoTuneOverrides_shouldReturnSuggestedOverridesWhenSamplesEnough() {
        LocalDate today = LocalDate.now();
        String key = "jarvis:knowledge:feedback:" + today + ":ws:1";
        when(workspaceService.resolveWorkspaceId(1L, 1001L)).thenReturn(1L);
        when(hashOperations.entries(key)).thenReturn(Map.of(
                "totalFeedback", "5",
                "helpfulFeedback", "4",
                "bias.vector.total", "4",
                "bias.vector.helpful", "4",
                "bias.keyword.total", "1",
                "bias.keyword.helpful", "0"
        ));

        KnowledgeBaseService.SearchOverrides overrides = knowledgeRetrievalPolicyService.resolveAutoTuneOverrides(1001L, 1L);

        assertNotNull(overrides);
        assertEquals(0.35, overrides.hybridKeywordWeight(), 0.0001);
        assertEquals(0.65, overrides.hybridVectorWeight(), 0.0001);
    }
}
