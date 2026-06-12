package com.thesisguard.review;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReviewNewsAnalysisAiResponse(
        @JsonProperty("news_title") String newsTitle,
        String analysis,
        @JsonProperty("impact_level") String impactLevel
) {
}
