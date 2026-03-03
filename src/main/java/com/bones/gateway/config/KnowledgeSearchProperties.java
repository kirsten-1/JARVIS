package com.bones.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jarvis.knowledge.search")
public class KnowledgeSearchProperties {

    private String defaultMode = "hybrid";
    private int maxCandidates = 200;
    private int defaultLimit = 8;
    private int maxLimit = 20;
    private double vectorMinSimilarity = 0.15;
    private double hybridMinScore = 0.10;
    private double hybridKeywordWeight = 0.65;
    private double hybridVectorWeight = 0.35;

    public String getDefaultMode() {
        return defaultMode;
    }

    public void setDefaultMode(String defaultMode) {
        this.defaultMode = defaultMode;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    public int getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(int defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    public int getMaxLimit() {
        return maxLimit;
    }

    public void setMaxLimit(int maxLimit) {
        this.maxLimit = maxLimit;
    }

    public double getVectorMinSimilarity() {
        return vectorMinSimilarity;
    }

    public void setVectorMinSimilarity(double vectorMinSimilarity) {
        this.vectorMinSimilarity = vectorMinSimilarity;
    }

    public double getHybridMinScore() {
        return hybridMinScore;
    }

    public void setHybridMinScore(double hybridMinScore) {
        this.hybridMinScore = hybridMinScore;
    }

    public double getHybridKeywordWeight() {
        return hybridKeywordWeight;
    }

    public void setHybridKeywordWeight(double hybridKeywordWeight) {
        this.hybridKeywordWeight = hybridKeywordWeight;
    }

    public double getHybridVectorWeight() {
        return hybridVectorWeight;
    }

    public void setHybridVectorWeight(double hybridVectorWeight) {
        this.hybridVectorWeight = hybridVectorWeight;
    }
}
