package com.thesisguard.review;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AutoReviewResponse(
        @JsonProperty("new_items_count") int newItemsCount,
        DailyNewsReviewResponse review
) {
}
