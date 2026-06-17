package com.thesisguard.openbb;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

// One annual income statement from /api/v1/equity/fundamental/income, used for revenue and
// year-over-year revenue growth context.
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenBbIncomeItem(
        @JsonProperty("period_ending")
        @JsonAlias({"date", "fiscal_date_ending"})
        LocalDate periodEnding,
        @JsonProperty("total_revenue")
        @JsonAlias({"revenue"})
        Double totalRevenue
) {}
