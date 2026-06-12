package com.thesisguard.openbb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenBbInsiderTransaction(
        String symbol,
        @JsonProperty("filing_date") LocalDate filingDate,
        @JsonProperty("transaction_date") LocalDate transactionDate,
        @JsonProperty("owner_name") String ownerName,
        @JsonProperty("owner_title") String ownerTitle,
        Boolean director,
        Boolean officer,
        @JsonProperty("transaction_type") String transactionType,
        @JsonProperty("acquisition_or_disposition") String acquisitionOrDisposition,
        @JsonProperty("security_type") String securityType,
        @JsonProperty("securities_transacted") BigDecimal securitiesTransacted,
        @JsonProperty("securities_owned") BigDecimal securitiesOwned,
        @JsonProperty("transaction_price") BigDecimal transactionPrice,
        @JsonProperty("filing_url") String filingUrl,
        String footnote
) {
}