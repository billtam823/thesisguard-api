package com.thesisguard.openbb;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// Valuation metrics from /api/v1/equity/fundamental/metrics. yfinance naming varies between
// OpenBB versions, so accept the common aliases for the price-to-sales field.
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenBbMetrics(
        @JsonProperty("price_to_sales")
        @JsonAlias({"price_sales_ratio", "ps_ratio", "price_to_sales_ttm"})
        Double priceToSales,
        @JsonProperty("pe_ratio")
        @JsonAlias({"price_to_earnings", "pe_ratio_ttm"})
        Double peRatio,
        @JsonProperty("market_cap")
        @JsonAlias({"market_capitalization"})
        Double marketCap
) {}
