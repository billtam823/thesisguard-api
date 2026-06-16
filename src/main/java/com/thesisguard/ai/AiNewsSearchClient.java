package com.thesisguard.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thesisguard.news.FetchedNewsItemResponse;
import com.thesisguard.stock.Stock;
import com.thesisguard.thesis.StockThesis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Collects recent company news by grounding an OpenRouter model in real web search results via the
 * {@code openrouter:web_search} server tool. URLs come from the returned citations (not the model's
 * free text) and are liveness-checked, so this is a reliable, cited supplement to Finnhub rather
 * than a hallucination-prone "ask the LLM for news" call.
 *
 * <p>Fail-safe: returns an empty list (never throws) when disabled, unconfigured, or on any error,
 * so it can be dropped into the ingest pipeline alongside the other sources.
 */
@Component
public class AiNewsSearchClient {
    private static final Logger log = LoggerFactory.getLogger(AiNewsSearchClient.class);
    private static final String BASE_URL = "https://openrouter.ai/api/v1";
    private static final int URL_CHECK_TIMEOUT_MS = 4000;

    private final RestClient openRouter; // null when no api-key
    private final RestClient livenessClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final boolean hasKey;
    private final boolean enabled;
    private final int lookbackHours;
    private final int maxItems;
    private final boolean verifyUrls;
    private final String engine;
    private final String systemPrompt;
    private final String userTemplate;

    public AiNewsSearchClient(OpenRouterProperties properties, ObjectMapper objectMapper,
                              @Value("${thesisguard.ai-news-search.enabled:true}") boolean enabled,
                              @Value("${thesisguard.ai-news-search.lookback-hours:24}") int lookbackHours,
                              @Value("${thesisguard.ai-news-search.max-items:25}") int maxItems,
                              @Value("${thesisguard.ai-news-search.verify-urls:true}") boolean verifyUrls,
                              @Value("${thesisguard.ai-news-search.engine:exa}") String engine) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.lookbackHours = lookbackHours;
        this.maxItems = maxItems;
        this.verifyUrls = verifyUrls;
        this.engine = engine;
        this.hasKey = properties.apiKey() != null && !properties.apiKey().isBlank();
        this.openRouter = hasKey
                ? RestClient.builder().baseUrl(BASE_URL)
                    .defaultHeader("Authorization", "Bearer " + properties.apiKey())
                    .defaultHeader("Content-Type", "application/json")
                    .build()
                : null;
        this.model = firstNonBlank(properties.newsSearchModel(), properties.reviewModel(), properties.model(),
                "openai/gpt-5.4-nano");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                // Don't follow redirects: a 3xx to an internal host must not bypass the SSRF check.
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(URL_CHECK_TIMEOUT_MS);
        factory.setReadTimeout(URL_CHECK_TIMEOUT_MS);
        this.livenessClient = RestClient.builder().requestFactory(factory).build();
        this.systemPrompt = loadPrompt("prompts/news_search_system_prompt.txt");
        this.userTemplate = loadPrompt("prompts/news_search_user_template.txt");
    }

    public List<FetchedNewsItemResponse> searchRecentNews(Stock stock, StockThesis thesis) {
        if (!enabled || !hasKey) {
            return List.of();
        }
        try {
            String content = callSearch(stock, thesis);
            ParsedResponse parsed = parseResponse(content);
            List<FetchedNewsItemResponse> candidates = mapItems(stock, parsed);
            List<FetchedNewsItemResponse> live = verifyUrls ? keepReachable(candidates) : candidates;
            List<FetchedNewsItemResponse> capped = live.size() > maxItems ? live.subList(0, maxItems) : live;
            log.debug("[AiNewsSearch] {} -> {} candidate(s), {} after liveness", stock.getTicker(), candidates.size(), capped.size());
            return capped;
        } catch (Exception ex) {
            log.warn("[AiNewsSearch] search failed for {}: {}", stock.getTicker(), ex.getMessage());
            return List.of();
        }
    }

    private String callSearch(Stock stock, StockThesis thesis) {
        OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime start = end.minusHours(lookbackHours);
        String thesisSummary = thesis != null && thesis.getSavedBuyThesisSummary() != null
                ? truncate(thesis.getSavedBuyThesisSummary(), 600) : "(none)";
        String userPrompt = userTemplate
                .replace("{{ticker}}", stock.getTicker())
                .replace("{{companyName}}", stock.getCompanyName() == null ? stock.getTicker() : stock.getCompanyName())
                .replace("{{windowStart}}", start.toString())
                .replace("{{windowEnd}}", end.toString())
                .replace("{{thesisSummary}}", thesisSummary);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        ArrayNode tools = body.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("type", "openrouter:web_search");
        ObjectNode params = tool.putObject("parameters");
        // exa returns url_citation annotations (the anti-hallucination guard relies on them);
        // auto routes to provider-native search which may omit citations for some models.
        params.put("engine", engine);
        params.put("max_results", 8);
        params.put("max_total_results", Math.max(8, maxItems));
        params.put("search_context_size", "medium");
        body.put("temperature", 0.2);
        body.put("max_tokens", 8000);

        String raw = openRouter.post().uri("/chat/completions").body(body).retrieve().body(String.class);
        log.debug("[AiNewsSearch] raw response ({} chars)", raw == null ? 0 : raw.length());
        return raw;
    }

    private record ParsedResponse(JsonNode news, boolean noReliableNews, Set<String> citedUrls) {}

    private ParsedResponse parseResponse(String raw) throws IOException {
        JsonNode root = objectMapper.readTree(raw);
        JsonNode message = root.path("choices").path(0).path("message");

        // Collect cited URLs from the web_search tool's annotations (shape can vary by provider).
        Set<String> cited = new HashSet<>();
        for (JsonNode ann : message.path("annotations")) {
            String url = ann.hasNonNull("url") ? ann.get("url").asText() : ann.path("url_citation").path("url").asText("");
            if (!url.isBlank()) {
                cited.add(normalizeUrl(url));
            }
        }

        JsonNode content = objectMapper.readTree(stripFences(message.path("content").asText("")));
        return new ParsedResponse(content.path("news"), content.path("no_reliable_news").asBoolean(false), cited);
    }

    private List<FetchedNewsItemResponse> mapItems(Stock stock, ParsedResponse parsed) {
        List<FetchedNewsItemResponse> items = new ArrayList<>();
        if (parsed.noReliableNews() || !parsed.news().isArray()) {
            return items;
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode node : parsed.news()) {
            String url = node.path("url").asText("").trim();
            String title = node.path("title").asText("").trim();
            if (url.isBlank() || title.isBlank()) {
                continue;
            }
            // SSRF guard: never store (or later probe) a non-public/internal URL.
            if (!isPublicHttpUrl(url)) {
                log.debug("[AiNewsSearch] dropping non-public url: {}", url);
                continue;
            }
            // Anti-hallucination: when we have citations, only trust URLs the search tool actually returned.
            if (!parsed.citedUrls().isEmpty() && !citationBacked(node, parsed.citedUrls())) {
                log.debug("[AiNewsSearch] dropping non-cited url: {}", url);
                continue;
            }
            if (!seen.add(normalizeUrl(url))) {
                continue;
            }
            items.add(new FetchedNewsItemResponse(
                    stock.getTicker(),
                    title.length() > 500 ? title.substring(0, 500) : title,
                    url,
                    parseDate(node.path("published_datetime").asText("")),
                    node.path("source").asText("AI Search"),
                    node.path("summary").asText(null)));
        }
        return items;
    }

    private boolean citationBacked(JsonNode item, Set<String> cited) {
        if (cited.contains(normalizeUrl(item.path("url").asText("")))) {
            return true;
        }
        for (JsonNode s : item.path("secondary_sources")) {
            if (cited.contains(normalizeUrl(s.asText("")))) {
                return true;
            }
        }
        return false;
    }

    private List<FetchedNewsItemResponse> keepReachable(List<FetchedNewsItemResponse> items) {
        if (items.isEmpty()) {
            return items;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, items.size()));
        try {
            List<Future<FetchedNewsItemResponse>> futures = new ArrayList<>();
            for (FetchedNewsItemResponse item : items) {
                Callable<FetchedNewsItemResponse> task = () -> isReachable(item.url()) ? item : null;
                futures.add(pool.submit(task));
            }
            List<FetchedNewsItemResponse> alive = new ArrayList<>();
            for (Future<FetchedNewsItemResponse> f : futures) {
                try {
                    FetchedNewsItemResponse r = f.get(URL_CHECK_TIMEOUT_MS + 1000L, TimeUnit.MILLISECONDS);
                    if (r != null) {
                        alive.add(r);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException | TimeoutException ex) {
                    // Treat as unreachable -> drop.
                }
            }
            return alive;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Best-effort liveness: drop only on clear "gone" signals and connection failures. */
    private boolean isReachable(String url) {
        if (!isPublicHttpUrl(url)) {
            return false; // SSRF guard (defense in depth; mapItems already filters)
        }
        try {
            livenessClient.head().uri(url).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            // 404/410 = gone; other statuses (401/403/405/429/5xx) often just block bots — keep.
            return status != 404 && status != 410;
        } catch (Exception ex) {
            return false; // unknown host / no route / timeout
        }
    }

    /**
     * SSRF allow-rule: the URL must be http/https and resolve only to public addresses. Rejects
     * loopback/link-local (incl. 169.254.169.254 metadata)/site-local (RFC1918)/any-local/multicast
     * and the 100.64.0.0/10 CGNAT range. Note: a single resolution leaves a small DNS-rebinding
     * window — acceptable here since responses are never returned to callers (blind liveness only).
     */
    boolean isPublicHttpUrl(String url) {
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                        || addr.isAnyLocalAddress() || addr.isMulticastAddress() || isCgnat(addr)) {
                    return false;
                }
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isCgnat(InetAddress addr) {
        byte[] b = addr.getAddress();
        // 100.64.0.0/10 (carrier-grade NAT) is not covered by the standard isXxxLocal checks.
        return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xFF) >= 64 && (b[1] & 0xFF) <= 127;
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return LocalDate.now();
        }
        try {
            return OffsetDateTime.parse(value).toLocalDate();
        } catch (Exception ignored) {
            try {
                return LocalDate.parse(value.substring(0, Math.min(10, value.length())));
            } catch (Exception ex) {
                return LocalDate.now();
            }
        }
    }

    private String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim().toLowerCase();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private String stripFences(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("(?s)^```[a-zA-Z]*\\n?", "").replaceFirst("(?s)\\n?```\\s*$", "").trim();
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        return start >= 0 && end > start ? t.substring(start, end + 1) : t;
    }

    private String truncate(String value, int max) {
        String cleaned = value.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private String loadPrompt(String path) {
        try (var in = new org.springframework.core.io.ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load AI prompt resource: " + path, ex);
        }
    }
}
