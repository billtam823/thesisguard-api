package com.thesisguard.thesis;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record StockThesisUpdateRequest(
        @NotBlank @JsonProperty("full_buy_thesis") String fullBuyThesis,
        @NotBlank @JsonProperty("saved_buy_thesis_summary") String savedBuyThesisSummary,
        @NotBlank @JsonProperty("final_rating") String finalRating,
        @NotBlank String conviction,
        @NotBlank @JsonProperty("portfolio_role") String portfolioRole,
        @NotBlank @JsonProperty("core_thesis") String coreThesis,
        @NotBlank @JsonProperty("business_essence") String businessEssence,
        @NotBlank @JsonProperty("growth_drivers") String growthDrivers,
        @NotBlank @JsonProperty("moat_summary") String moatSummary,
        @NotBlank @JsonProperty("financial_quality") String financialQuality,
        @NotBlank @JsonProperty("valuation_view") String valuationView,
        @NotBlank @JsonProperty("main_risks") String mainRisks,
        @NotBlank @JsonProperty("thesis_break_triggers") String thesisBreakTriggers,
        @NotBlank @JsonProperty("daily_review_focus") String dailyReviewFocus
) {
}
