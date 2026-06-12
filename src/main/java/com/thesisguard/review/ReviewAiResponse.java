package com.thesisguard.review;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ReviewAiResponse(
        @JsonProperty("thesis_change_level") ThesisChangeLevel thesisChangeLevel,
        String summary,
        @JsonProperty("thesis_impact") String thesisImpact,
        @JsonProperty("recommended_action") String recommendedAction,
        @JsonProperty("news_analysis") List<ReviewNewsAnalysisAiResponse> newsAnalysis
) {
}
