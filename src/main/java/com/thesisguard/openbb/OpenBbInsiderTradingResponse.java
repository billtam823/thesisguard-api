package com.thesisguard.openbb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenBbInsiderTradingResponse(
        List<OpenBbInsiderTransaction> results
) {
}