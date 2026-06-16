package com.thesisguard.finnhub;

import com.thesisguard.common.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

/**
 * Thin client for Finnhub's REST API (https://finnhub.io/docs/api). The company-news source:
 * its date-range /company-news returns all articles in the window (unlike OpenBB's yfinance, which
 * caps at ~10). SEC filings/insider trades and exchange lookups still go through OpenBB.
 */
@Component
public class FinnhubClient {
    private static final Logger log = LoggerFactory.getLogger(FinnhubClient.class);

    private final RestClient restClient;
    private final String apiKey;

    public FinnhubClient(FinnhubProperties properties) {
        this.restClient = RestClient.builder().baseUrl(properties.baseUrl()).build();
        this.apiKey = properties.apiKey();
    }

    /**
     * Company news in the [from, to] date range (inclusive). Finnhub requires both dates.
     * Returns an empty list (rather than throwing) when no API key is configured, so the rest
     * of the ingest still runs.
     */
    public List<FinnhubNewsItem> fetchCompanyNews(String symbol, LocalDate from, LocalDate to) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[Finnhub] No api-key configured; skipping company-news fetch for {}", symbol);
            return List.of();
        }
        try {
            FinnhubNewsItem[] response = restClient.get()
                    .uri(b -> b.path("/company-news")
                            .queryParam("symbol", symbol)
                            .queryParam("from", from.toString())
                            .queryParam("to", to.toString())
                            .build())
                    // Auth via header, not a query param, so the key never lands in URLs or access logs.
                    .header("X-Finnhub-Token", apiKey)
                    .retrieve()
                    .body(FinnhubNewsItem[].class);

            return response == null ? List.of() : List.of(response);
        } catch (Exception ex) {
            // Don't surface ex.getMessage(): it can include the request URL.
            log.debug("[Finnhub] company-news fetch failed for {}: {}", symbol, ex.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to fetch news from Finnhub");
        }
    }
}
