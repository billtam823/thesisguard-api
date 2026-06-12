package com.thesisguard.openbb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenBbNewsItem(
        String symbol,
        String title,
        String url,
        OffsetDateTime date,
        String source,
        String summary
) {
}