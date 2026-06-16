package com.thesisguard.openbb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenBbEquitySearchItem(
        String symbol,
        String name,
        String cik
) {
}
