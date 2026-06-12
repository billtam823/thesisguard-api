package com.thesisguard.review;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NewsAnalysisItemResponse(
        Long id,
        @JsonProperty("news_item_id") Long newsItemId,
        @JsonProperty("news_title") String newsTitle,
        String analysis,
        @JsonProperty("impact_level") String impactLevel
) {
    public static NewsAnalysisItemResponse from(NewsAnalysisItem item) {
        return new NewsAnalysisItemResponse(item.getId(), item.getNewsItem().getId(), item.getNewsItem().getTitle(), item.getAnalysis(), item.getImpactLevel());
    }
}
