package com.thesisguard.news;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record NewsItemResponse(
        Long id,
        @JsonProperty("stock_id") Long stockId,
        String title,
        String summary,
        String url,
        @JsonProperty("published_date") LocalDate publishedDate,
        String source,
        @JsonProperty("reviewed_at") LocalDateTime reviewedAt,
        @JsonProperty("impact_level") String impactLevel,
        @JsonProperty("related_to_stock") Boolean relatedToStock,
        String analysis,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt
) {
    public static NewsItemResponse from(NewsItem item) {
        return new NewsItemResponse(item.getId(), item.getStock().getId(), item.getTitle(), item.getSummary(), item.getUrl(), item.getPublishedDate(), item.getSource(), item.getReviewedAt(), item.getImpactLevel(), item.getRelatedToStock(), item.getAnalysis(), item.getCreatedAt(), item.getUpdatedAt());
    }
}
