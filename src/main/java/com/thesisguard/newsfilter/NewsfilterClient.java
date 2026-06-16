package com.thesisguard.newsfilter;

import com.thesisguard.common.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

/**
 * Thin client for newsfilter.io's Query API (https://developers.newsfilter.io). The company-news
 * source: POST /search with a query-string DSL filtering by ticker symbol and publishedAt range.
 * Returns an empty list (rather than throwing) when no api-key is configured, so the rest of the
 * ingest still runs.
 */
@Component
public class NewsfilterClient {
    private static final Logger log = LoggerFactory.getLogger(NewsfilterClient.class);
    private static final String DEFAULT_BASE_URL = "https://api.newsfilter.io";
    private static final int FETCH_SIZE = 100;

    private final RestClient restClient;
    private final String apiKey;

    public NewsfilterClient(NewsfilterProperties properties) {
        String baseUrl = properties.baseUrl() != null && !properties.baseUrl().isBlank()
                ? properties.baseUrl() : DEFAULT_BASE_URL;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = properties.apiKey();
    }

    /**
     * Company news with publishedAt in the [from, to] date range (inclusive). Auth via the
     * Authorization header (the API's recommended method; value is the raw key, no "Bearer" prefix),
     * keeping the token out of request URLs and access logs.
     */
    public List<NewsfilterArticle> fetchCompanyNews(String symbol, LocalDate from, LocalDate to) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[Newsfilter] No api-key configured; skipping company-news fetch for {}", symbol);
            return List.of();
        }
        String queryString = "symbols:%s AND publishedAt:[%s TO %s]".formatted(symbol, from, to);
        try {
            NewsfilterSearchResponse response = restClient.post()
                    .uri("/search")
                    .header("Authorization", apiKey)
                    .header("Content-Type", "application/json")
                    .body(new SearchRequest(queryString, 0, FETCH_SIZE))
                    .retrieve()
                    .body(NewsfilterSearchResponse.class);

            return response == null || response.articles() == null ? List.of() : response.articles();
        } catch (Exception ex) {
            log.debug("[Newsfilter] company-news fetch failed for {}: {}", symbol, ex.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to fetch news from Newsfilter");
        }
    }

    private record SearchRequest(String queryString, int from, int size) {}
}
