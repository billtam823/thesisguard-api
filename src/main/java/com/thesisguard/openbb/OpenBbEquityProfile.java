package com.thesisguard.openbb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenBbEquityProfile(
        String symbol,
        @JsonProperty("stock_exchange") String stockExchange
) {}
