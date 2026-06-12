package com.thesisguard.openbb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenBbFilingItem(
        @JsonProperty("filing_date") LocalDate filingDate,
        @JsonProperty("report_type") String reportType,
        @JsonProperty("report_url") String reportUrl,
        @JsonProperty("filing_detail_url") String filingDetailUrl,
        @JsonProperty("primary_doc_description") String primaryDocDescription,
        String items
) {
}