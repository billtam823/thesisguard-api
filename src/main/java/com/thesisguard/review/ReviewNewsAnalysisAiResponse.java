package com.thesisguard.review;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReviewNewsAnalysisAiResponse(
        @JsonProperty("news_item_id") String newsItemId,
        String analysis,
        @JsonProperty("impact_level") String impactLevel
) {
}
