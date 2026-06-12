package com.thesisguard.alert;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public record AlertResponse(
        Long id,
        @JsonProperty("stock_id") Long stockId,
        @JsonProperty("stock_code") String stockCode,
        String exchange,
        @JsonProperty("daily_news_review_id") Long dailyNewsReviewId,
        AlertSeverity severity,
        String title,
        String message,
        boolean resolved,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt,
        @JsonProperty("resolved_at") LocalDateTime resolvedAt
) {
    public static AlertResponse from(Alert alert) {
        Long reviewId = alert.getDailyNewsReview() == null ? null : alert.getDailyNewsReview().getId();
        return new AlertResponse(
                alert.getId(),
                alert.getStock().getId(),
                alert.getStock().getTicker(),
                alert.getStock().getExchange(),
                reviewId,
                alert.getSeverity(),
                alert.getTitle(),
                alert.getMessage(),
                alert.isResolved(),
                alert.getCreatedAt(),
                alert.getUpdatedAt(),
                alert.getResolvedAt()
        );
    }
}
