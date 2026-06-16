package com.thesisguard.seekingalpha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thesisguard.common.exception.ApiException;
import com.thesisguard.news.FetchedNewsItemResponse;
import com.thesisguard.stock.Stock;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Company-news source backed by the Seeking Alpha API on RapidAPI
 * (host {@code seeking-alpha-finance.p.rapidapi.com}, endpoint {@code GET /v1/symbols/news}).
 * Auth is via the {@code x-rapidapi-key} header (kept out of URLs). Returns an empty list (rather
 * than throwing) when no key is configured, so the rest of the ingest still runs.
 */
@Component
public class SeekingAlphaClient {
    private static final Logger log = LoggerFactory.getLogger(SeekingAlphaClient.class);
    private static final String DEFAULT_HOST = "seeking-alpha-finance.p.rapidapi.com";
    private static final String ARTICLE_BASE = "https://seekingalpha.com";
    private static final String SOURCE = "Seeking Alpha";

    private final RestClient restClient; // null when no api-key
    private final ObjectMapper objectMapper;
    private final boolean hasKey;

    public SeekingAlphaClient(SeekingAlphaProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.hasKey = properties.apiKey() != null && !properties.apiKey().isBlank();
        String host = properties.host() != null && !properties.host().isBlank() ? properties.host() : DEFAULT_HOST;
        this.restClient = hasKey
                ? RestClient.builder()
                    .baseUrl("https://" + host)
                    .defaultHeader("x-rapidapi-key", properties.apiKey())
                    .defaultHeader("x-rapidapi-host", host)
                    .build()
                : null;
    }

    /** Latest news for the stock (the API returns ~40 most-recent items, newest first). */
    public List<FetchedNewsItemResponse> fetchCompanyNews(Stock stock) {
        if (!hasKey) {
            log.warn("[SeekingAlpha] No api-key configured; skipping company-news fetch for {}", stock.getTicker());
            return List.of();
        }
        String slug = stock.getTicker().toLowerCase();
        try {
            String raw = restClient.get()
                    .uri(b -> b.path("/v1/symbols/news")
                            .queryParam("category", "all")
                            .queryParam("ticker_slug", slug)
                            .queryParam("page_number", 1)
                            .build())
                    .retrieve()
                    .body(String.class);
            return parse(raw, stock.getTicker());
        } catch (Exception ex) {
            log.debug("[SeekingAlpha] company-news fetch failed for {}: {}", stock.getTicker(), ex.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to fetch news from Seeking Alpha");
        }
    }

    private List<FetchedNewsItemResponse> parse(String raw, String ticker) throws Exception {
        List<FetchedNewsItemResponse> items = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return items;
        }
        JsonNode root = objectMapper.readTree(raw);
        for (JsonNode node : root.path("data")) {
            JsonNode attr = node.path("attributes");
            String title = attr.path("title").asText("").trim();
            if (title.isBlank()) {
                continue;
            }
            String self = node.path("links").path("self").asText("");
            String url = self.isBlank() ? null : (self.startsWith("http") ? self : ARTICLE_BASE + self);
            String content = strip(attr.path("content").asText(""));
            items.add(new FetchedNewsItemResponse(
                    ticker,
                    title,
                    url,
                    parseDate(attr.path("publishOn").asText("")),
                    SOURCE,
                    lede(content),   // short blurb for the feed
                    content));       // full article for the review
        }
        return items;
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toLocalDate();
        } catch (Exception ex) {
            try {
                return LocalDate.parse(value.substring(0, Math.min(10, value.length())));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    // Full article text (the review reads this), bounded so one huge article can't blow up storage
    // or the review prompt; and the short blurb shown in the feed.
    private static final int CONTENT_MAX = 6000;
    private static final int SUMMARY_MAX = 300;

    /** Extract article paragraph text only, discarding figures, widgets, and related-article sections. */
    private String strip(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        Document doc = Jsoup.parseBodyFragment(html);
        // Remove non-article elements before extracting text
        doc.select("figure, script, style, [class*=placeholder], [class*=signup], [class*=widget]").remove();
        StringJoiner sj = new StringJoiner(" ");
        for (Element p : doc.select("p")) {
            String t = p.text().trim();
            if (!t.isEmpty()) {
                sj.add(t);
            }
        }
        String text = sj.toString().trim();
        if (text.isBlank()) {
            return null;
        }
        return text.length() <= CONTENT_MAX ? text : text.substring(0, CONTENT_MAX) + "…";
    }

    /** Short blurb (lede) for the feed, derived from the stripped content. */
    private String lede(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= SUMMARY_MAX ? content : content.substring(0, SUMMARY_MAX) + "…";
    }
}
