package com.thesisguard.review;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record DailyNewsReviewResponse(
        Long id,
        @JsonProperty("stock_id") Long stockId,
        @JsonProperty("review_date") LocalDate reviewDate,
        @JsonProperty("thesis_change_level") ThesisChangeLevel thesisChangeLevel,
        String summary,
        @JsonProperty("thesis_impact") String thesisImpact,
        @JsonProperty("recommended_action") String recommendedAction,
        @JsonProperty("news_analysis") List<NewsAnalysisItemResponse> newsAnalysis,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt
) {
    public static DailyNewsReviewResponse from(DailyNewsReview review) {
        return new DailyNewsReviewResponse(review.getId(), review.getStock().getId(), review.getReviewDate(), review.getThesisChangeLevel(), review.getSummary(), review.getThesisImpact(), review.getRecommendedAction(), review.getAnalysisItems().stream().map(NewsAnalysisItemResponse::from).toList(), review.getCreatedAt(), review.getUpdatedAt());
    }
}
