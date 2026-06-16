package com.thesisguard.news;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Schedule for the automatic news fetch. Each cron expression registers one twice-daily-style
 * run (e.g. pre-market and lunch). An empty list disables automatic fetching entirely, in which
 * case news is only ingested on demand via the ingest/auto-review endpoints.
 */
@ConfigurationProperties(prefix = "thesisguard.news-fetch")
public record NewsFetchProperties(List<String> crons, String zone) {
    public NewsFetchProperties {
        if (crons == null) {
            crons = List.of();
        }
        if (zone == null || zone.isBlank()) {
            zone = "UTC";
        }
    }
}
