package com.thesisguard.thesis;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public record StockThesisResponse(
        Long id,
        @JsonProperty("stock_id") Long stockId,
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
        @JsonProperty("return_basis") String returnBasis,
        @JsonProperty("generation_status") String generationStatus,
        @JsonProperty("generation_error") String generationError,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt
) {
    public static StockThesisResponse from(StockThesis thesis) {
        return new StockThesisResponse(thesis.getId(), thesis.getStock().getId(), thesis.getFullBuyThesis(), thesis.getSavedBuyThesisSummary(), thesis.getFinalRating(), thesis.getConviction(), thesis.getPortfolioRole(), thesis.getCoreThesis(), thesis.getBusinessEssence(), thesis.getGrowthDrivers(), thesis.getMoatSummary(), thesis.getFinancialQuality(), thesis.getValuationView(), thesis.getMainRisks(), thesis.getThesisBreakTriggers(), thesis.getDailyReviewFocus(), thesis.getReturnMultiple(), thesis.getReturnBasis(), thesis.getGenerationStatus(), thesis.getGenerationError(), thesis.getCreatedAt(), thesis.getUpdatedAt());
    }
}
