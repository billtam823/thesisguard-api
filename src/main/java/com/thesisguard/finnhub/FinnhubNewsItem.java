package com.thesisguard.finnhub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One item from Finnhub's /company-news response.
 * See https://finnhub.io/docs/api/company-news — {@code datetime} is a UNIX timestamp (seconds).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FinnhubNewsItem(
        String category,
        long datetime,
        String headline,
        long id,
        String image,
        String related,
        String source,
        String summary,
        String url
) {
}
