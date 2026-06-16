package com.thesisguard.newsfilter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One article from newsfilter.io's /search response. See
 * https://developers.newsfilter.io/docs/news-query-api-request-response-formats.html
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NewsfilterArticle(
        String id,
        String title,
        String description,
        String sourceUrl,
        String imageUrl,
        String publishedAt,
        Source source,
        List<String> symbols
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String id, String name) {}
}
