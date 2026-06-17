package com.thesisguard.thesis;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ThesisAiResponse(
        @JsonProperty("full_buy_thesis") String fullBuyThesis,
        @JsonProperty("saved_buy_thesis_summary") String savedBuyThesisSummary,
        @JsonProperty("final_rating") String finalRating,
        String conviction,
        @JsonProperty("portfolio_role") String portfolioRole,
        @JsonProperty("core_thesis") String coreThesis,
        @JsonProperty("business_essence") String businessEssence,
        @JsonProperty("growth_drivers") String growthDrivers,
        @JsonProperty("moat_summary") String moatSummary,
        @JsonProperty("financial_quality") String financialQuality,
        @JsonProperty("valuation_view") String valuationView,
        @JsonProperty("main_risks") String mainRisks,
        @JsonProperty("thesis_break_triggers") String thesisBreakTriggers,
        @JsonProperty("daily_review_focus") String dailyReviewFocus,
        @JsonProperty("return_multiple") String returnMultiple,
        @JsonProperty("return_basis") String returnBasis
) {
}
