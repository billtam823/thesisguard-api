package com.thesisguard.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thesisguard.common.exception.ApiException;
import com.thesisguard.openbb.FundamentalsSnapshot;
import com.thesisguard.openbb.OpenBbClient;
import com.thesisguard.news.NewsItem;
import com.thesisguard.review.NewsTriageResult;
import com.thesisguard.review.ReviewAiResponse;
import com.thesisguard.review.ReviewNewsAnalysisAiResponse;
import com.thesisguard.review.ThesisChangeLevel;
import com.thesisguard.stock.Stock;
import com.thesisguard.thesis.StockThesis;
import com.thesisguard.thesis.ThesisAiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(name = "openrouter.api-key")
public class OpenRouterAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterAiClient.class);
    private static final String BASE_URL = "https://openrouter.ai/api/v1";
    private static final String NO_DATA = "(none)";
    private static final Pattern CODE_BLOCK = Pattern.compile("(?s)```[a-zA-Z]*\\n?(\\{.*?\\})\\n?```");
    // Ordered weakest -> strongest; index used to clamp the forecast to a size-based ceiling.
    private static final List<String> RETURN_BUCKETS = List.of("2x", "3-5x", "5-10x", "10x+");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final OpenBbClient openBbClient;
    private final String thesisModel;
    private final String reviewModel;
    private final String triageModel;
    private final String buyThesisSystemPrompt;
    private final String buyThesisUserTemplate;
    private final ResponseFormat buyThesisResponseFormat;
    private final String dailyReviewSystemPrompt;
    private final String dailyReviewUserTemplate;
    private final ResponseFormat reviewResponseFormat;
    private final String newsTriageSystemPrompt;
    private final String newsTriageUserTemplate;

    public OpenRouterAiClient(OpenRouterProperties properties, ObjectMapper objectMapper, OpenBbClient openBbClient) {
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Authorization", "Bearer " + properties.apiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.objectMapper = objectMapper;
        this.openBbClient = openBbClient;
        this.thesisModel = properties.model() != null ? properties.model() : "google/gemini-2.0-flash-exp:free";
        this.reviewModel = properties.reviewModel() != null ? properties.reviewModel() : this.thesisModel;
        // Triage is the cheap pre-filter; fall back to the review model when no cheap model is configured.
        this.triageModel = properties.triageModel() != null ? properties.triageModel() : this.reviewModel;
        this.buyThesisSystemPrompt = loadPrompt("prompts/buy_thesis_system_prompt.txt");
        this.buyThesisUserTemplate = loadPrompt("prompts/buy_thesis_user_template.txt");
        this.buyThesisResponseFormat = loadThesisResponseFormat();
        this.dailyReviewSystemPrompt = loadPrompt("prompts/daily_review_system_prompt.txt");
        this.dailyReviewUserTemplate = loadPrompt("prompts/daily_review_user_template.txt");
        this.reviewResponseFormat = loadReviewResponseFormat();
        this.newsTriageSystemPrompt = loadPrompt("prompts/news_triage_system_prompt.txt");
        this.newsTriageUserTemplate = loadPrompt("prompts/news_triage_user_template.txt");
    }

    @Override
    public ThesisAiResponse generateBuyThesis(Stock stock) {
        // Fetch real fundamentals once so the growth forecast and size cap use live numbers.
        FundamentalsSnapshot fundamentals = fetchFundamentalsSafe(stock);
        String fundamentalsJson = buildFundamentalsJson(stock, fundamentals);
        ThesisAiResponse response;
        try {
            String json = callOpenRouter(thesisModel, buyThesisSystemPrompt,
                    buildThesisUserPrompt(stock, fundamentalsJson), 0.4, 6000, buyThesisResponseFormat);
            response = mapThesisResponse(json);
        } catch (TruncatedJsonException ex) {
            log.debug("[OpenRouter] Thesis JSON truncated; retrying once with a larger completion budget");
            try {
                String retryJson = callOpenRouter(thesisModel, buyThesisSystemPrompt,
                        buildThesisUserPrompt(stock, fundamentalsJson), 0.2, 12000, buyThesisResponseFormat);
                response = mapThesisResponse(retryJson);
            } catch (TruncatedJsonException retryEx) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "OpenRouter model output was truncated even at 12,000 tokens. Switch to a model with a larger completion limit.");
            }
        }
        // The model proposes the forecast bucket; code clamps it to the law-of-large-numbers ceiling.
        return clampForecastToSize(response, fundamentals);
    }

    @Override
    public NewsTriageResult triageNews(Stock stock, StockThesis thesis, List<NewsItem> newsItems) {
        if (newsItems.isEmpty()) {
            return new NewsTriageResult(List.of());
        }
        List<Long> ids = newsItems.stream().map(NewsItem::getId).toList();
        try {
            String json = callOpenRouter(triageModel, newsTriageSystemPrompt,
                    buildTriageUserPrompt(stock, thesis, newsItems), 0.0, triageMaxTokens(newsItems.size()));
            return mapTriageResponse(json, newsItems);
        } catch (ApiException ex) {
            // Fail safe: never silently drop a possible signal. Escalate the whole group to the
            // full doctrine review when triage is unavailable (truncated/empty/parse/API failure).
            log.debug("[OpenRouter] Triage failed ({}); treating all {} item(s) as material", ex.getMessage(), newsItems.size());
            return NewsTriageResult.allMaterial(ids, "Triage unavailable; escalated to full review as a precaution.");
        }
    }

    private int triageMaxTokens(int newsCount) {
        // One short verdict per item (id + boolean + brief reason); keep the budget tight.
        return Math.min(6000, 400 + 80 * newsCount);
    }

    private String buildTriageUserPrompt(Stock stock, StockThesis thesis, List<NewsItem> newsItems) {
        return fill(newsTriageUserTemplate,
                "{{ticker}}", stock.getTicker(),
                "{{companyName}}", stock.getCompanyName(),
                "{{thesisDigest}}", buildCompactThesisText(thesis),
                "{{newsItemsBlock}}", buildNewsItemsBlock(newsItems, TRIAGE_SUMMARY_LEN, false));
    }

    private NewsTriageResult mapTriageResponse(String json, List<NewsItem> newsItems) {
        JsonNode root = parseTree(json);
        Map<Long, NewsTriageResult.Verdict> byId = new HashMap<>();
        root.path("items").forEach(item -> {
            Long id = parseId(text(item, "id"));
            if (id == null) {
                return;
            }
            boolean material = item.path("material").asBoolean(false);
            boolean related = item.path("related").asBoolean(true); // default related (fail-safe: don't hide news)
            byId.put(id, new NewsTriageResult.Verdict(id, material, related, truncate(text(item, "reason"), 200)));
        });
        // Any item the model omitted defaults to material and related (fail safe).
        List<NewsTriageResult.Verdict> verdicts = new ArrayList<>();
        for (NewsItem item : newsItems) {
            NewsTriageResult.Verdict verdict = byId.get(item.getId());
            verdicts.add(verdict != null
                    ? verdict
                    : new NewsTriageResult.Verdict(item.getId(), true, true, "No triage verdict returned; escalated as a precaution."));
        }
        return new NewsTriageResult(verdicts);
    }

    private Long parseId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Override
    public ReviewAiResponse reviewNews(Stock stock, StockThesis thesis, List<NewsItem> newsItems, String monitorMemory) {
        try {
            String json = callOpenRouter(reviewModel, dailyReviewSystemPrompt,
                    buildReviewUserPrompt(stock, thesis, newsItems, monitorMemory), 0.1,
                    reviewMaxTokens(newsItems.size()), reviewResponseFormat);
            return mapReviewResponse(json);
        } catch (TruncatedJsonException ex) {
            log.debug("[OpenRouter] Review JSON truncated; retrying once before falling back");
            try {
                String retry = callOpenRouter(reviewModel, dailyReviewSystemPrompt,
                        buildReviewUserPrompt(stock, thesis, newsItems, monitorMemory), 0.0,
                        Math.min(20000, reviewMaxTokens(newsItems.size()) * 2), reviewResponseFormat);
                return mapReviewResponse(retry);
            } catch (TruncatedJsonException retryEx) {
                log.debug("[OpenRouter] Review retry also truncated; using local conservative fallback");
                return localReviewFallback(stock, newsItems,
                        "OpenRouter output was truncated before valid JSON completed.");
            }
        }
    }

    private int reviewMaxTokens(int newsCount) {
        // Reasoning models spend hidden thinking tokens out of the same completion
        // budget, and itemAnalyses grows with every saved article, so a flat limit
        // truncates the JSON once either grows. The rewritten monitoring memory
        // (updatedMemory) also comes out of this budget.
        return Math.min(20000, 9000 + 250 * newsCount);
    }

    private String callOpenRouter(String model, String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        return callOpenRouter(model, systemPrompt, userPrompt, temperature, maxTokens, null);
    }

    // primaryFormat is the response_format sent on the first attempt (null = plain text, or a
    // json_schema spec for enforced structured outputs). On empty content we still retry json_object.
    private String callOpenRouter(String model, String systemPrompt, String userPrompt, double temperature,
                                  int maxTokens, ResponseFormat primaryFormat) {
        log.debug("[OpenRouter] Sending system prompt ({} chars) and user prompt ({} chars) to model '{}' (structured={})",
                systemPrompt.length(), userPrompt.length(), model, primaryFormat != null && primaryFormat.jsonSchema() != null);
        try {
            OpenRouterRequest primaryRequest = buildRequest(model, systemPrompt, userPrompt, temperature, maxTokens, primaryFormat);
            OpenRouterResponse response = executeRequest(primaryRequest);
            String text;
            try {
                text = extractText(response);
            } catch (EmptyMessageContentException ex) {
                log.debug("[OpenRouter] Empty content; retrying with response_format=json_object");
                OpenRouterRequest jsonRequest = buildRequest(model, systemPrompt, userPrompt, temperature, maxTokens, new ResponseFormat("json_object"));
                text = extractText(executeRequest(jsonRequest));
            }
            if (isLengthFinish(response) && !looksCompleteJson(text)) {
                throw new TruncatedJsonException(
                        "OpenRouter model output was truncated before valid JSON completed.");
            }
            log.debug("[OpenRouter] Raw response ({} chars):\n{}", text.length(), text);
            return text;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.debug("[OpenRouter] Call failed: {}", ex.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OpenRouter API call failed: " + ex.getMessage());
        }
    }

    private OpenRouterRequest buildRequest(String model, String systemPrompt, String userPrompt, double temperature,
                                           int maxTokens, ResponseFormat responseFormat) {
        boolean structured = responseFormat != null && responseFormat.jsonSchema() != null;
        // When enforcing a strict schema, require providers that honor it so failures are explicit.
        ProviderConfig provider = structured ? new ProviderConfig(true) : null;
        // Reasoning support is a per-MODEL trait, not per-request: the thesis model mandates reasoning
        // (effort:"none" is rejected), so use low-effort reasoning (excluded from output) for all its
        // calls (primary, compact fallback, json_object retry). Other models keep reasoning disabled.
        ReasoningConfig reasoning = model.equals(thesisModel)
                ? new ReasoningConfig("low", true)
                : new ReasoningConfig("none", true);
        return new OpenRouterRequest(
                model,
                List.of(new RequestMessage("system", systemPrompt), new RequestMessage("user", userPrompt)),
                responseFormat,
                temperature,
                maxTokens,
                reasoning,
                provider
        );
    }

    private boolean isLengthFinish(OpenRouterResponse response) {
        return isLengthFinish(response.choices().get(0));
    }

    private boolean isLengthFinish(Choice choice) {
        return "length".equalsIgnoreCase(choice.finishReason())
                || "length".equalsIgnoreCase(choice.nativeFinishReason());
    }

    private boolean looksCompleteJson(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}");
    }

    private OpenRouterResponse executeRequest(OpenRouterRequest request) {
        String rawResponse = restClient.post()
                .uri("/chat/completions")
                .body(request)
                .retrieve()
                .body(String.class);

        if (rawResponse == null || rawResponse.isBlank()) {
            log.debug("[OpenRouter] Blank response body received");
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Blank response from OpenRouter");
        }

        log.trace("[OpenRouter] Response envelope ({} chars):\n{}", rawResponse.length(), rawResponse);
        OpenRouterResponse response = parseEnvelope(rawResponse);
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            String errorMessage = response != null && response.error() != null ? response.error().message() : null;
            log.debug("[OpenRouter] Empty or null choices received; error={}", errorMessage);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Empty response from OpenRouter" + suffix(errorMessage));
        }
        logResponseSummary(response, rawResponse.length());
        return response;
    }

    private void logResponseSummary(OpenRouterResponse response, int rawLength) {
        Choice choice = response.choices().get(0);
        ResponseMessage message = choice.message();
        int contentLength = textContent(message != null ? message.content() : null) == null
                ? 0
                : textContent(message.content()).length();
        int reasoningLength = message != null && message.reasoning() != null ? message.reasoning().length() : 0;
        log.debug("[OpenRouter] Response envelope received: rawChars={}, finish_reason={}, native_finish_reason={}, contentChars={}, reasoningChars={}",
                rawLength, choice.finishReason(), choice.nativeFinishReason(), contentLength, reasoningLength);
    }

    private OpenRouterResponse parseEnvelope(String rawResponse) {
        try {
            return objectMapper.readValue(rawResponse, OpenRouterResponse.class);
        } catch (JsonProcessingException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to parse OpenRouter response envelope: " + ex.getMessage());
        }
    }

    private String loadPrompt(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load AI prompt resource: " + path, ex);
        }
    }

    // Loads the strict JSON schema that enforces the buy-thesis output shape (structured outputs).
    private ResponseFormat loadThesisResponseFormat() {
        try {
            JsonNode schema = objectMapper.readTree(loadPrompt("prompts/buy_thesis_schema.json"));
            return new ResponseFormat("json_schema", new JsonSchemaSpec("buy_thesis", true, schema));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load buy-thesis JSON schema", ex);
        }
    }

    private ResponseFormat loadReviewResponseFormat() {
        try {
            JsonNode schema = objectMapper.readTree(loadPrompt("prompts/review_schema.json"));
            return new ResponseFormat("json_schema", new JsonSchemaSpec("daily_review", true, schema));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load daily-review JSON schema", ex);
        }
    }

    private String extractText(OpenRouterResponse response) {
        Choice choice = response.choices().get(0);
        if (choice.message() == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OpenRouter response did not include a message"
                    + finishSuffix(choice));
        }

        String text = textContent(choice.message().content());
        if (text == null || text.isBlank()) {
            text = extractJsonFromAlternateFields(choice.message());
        }
        if (text == null || text.isBlank()) {
            String message = "OpenRouter response message content was empty" + finishSuffix(choice);
            if (isLengthFinish(choice)) {
                throw new TruncatedJsonException(message);
            }
            throw new EmptyMessageContentException(message);
        }
        text = text.trim();
        Matcher m = CODE_BLOCK.matcher(text);
        if (m.find()) {
            log.debug("[OpenRouter] Code block found; using its content");
            return m.group(1).trim();
        }
        if (text.startsWith("```")) {
            text = text.replaceFirst("(?s)^```[a-zA-Z]*\\n?", "").replaceFirst("(?s)\\n?```\\s*$", "").trim();
        }
        return text;
    }

    private String textContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            List<String> parts = new ArrayList<>();
            content.forEach(part -> {
                String text = text(part, "text");
                if (!text.isBlank()) {
                    parts.add(text);
                }
            });
            return String.join("\n", parts);
        }
        return content.toString();
    }

    private String extractJsonFromAlternateFields(ResponseMessage message) {
        String[] candidates = {message.reasoning(), message.refusal()};
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Matcher m = CODE_BLOCK.matcher(candidate);
            if (m.find()) {
                return m.group(1).trim();
            }
            int start = candidate.indexOf('{');
            int end = candidate.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return candidate.substring(start, end + 1);
            }
        }
        return null;
    }

    private String finishSuffix(Choice choice) {
        List<String> parts = new ArrayList<>();
        if (choice.finishReason() != null) {
            parts.add("finish_reason=" + choice.finishReason());
        }
        if (choice.nativeFinishReason() != null) {
            parts.add("native_finish_reason=" + choice.nativeFinishReason());
        }
        if (choice.error() != null && choice.error().message() != null) {
            parts.add("error=" + choice.error().message());
        }
        return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
    }

    private String suffix(String value) {
        return value == null || value.isBlank() ? "" : ": " + value;
    }

    ThesisAiResponse mapThesisResponse(String json) {
        JsonNode root = parseTree(json);
        return new ThesisAiResponse(
                text(root, "full_buy_thesis"),
                text(root, "saved_buy_thesis_summary"),
                text(root, "final_rating"),
                text(root, "conviction"),
                text(root, "portfolio_role"),
                text(root, "core_thesis"),
                text(root, "business_essence"),
                text(root, "growth_drivers"),
                text(root, "moat_summary"),
                text(root, "financial_quality"),
                text(root, "valuation_view"),
                text(root, "main_risks"),
                joinArray(root.path("thesis_break_triggers")),
                joinArray(root.path("daily_review_focus")),
                text(root, "return_multiple"),
                text(root, "return_basis")
        );
    }

    ReviewAiResponse mapReviewResponse(String json) {
        JsonNode root = parseTree(json);
        List<ReviewNewsAnalysisAiResponse> newsAnalysis = new ArrayList<>();
        root.path("item_analyses").forEach(item -> newsAnalysis.add(new ReviewNewsAnalysisAiResponse(
                text(item, "news_item_id"),
                text(item, "analysis"),
                text(item, "item_change_level")
        )));
        return new ReviewAiResponse(
                mapChangeLevel(text(root, "change_level")),
                text(root, "news_summary"),
                text(root, "thesis_impact"),
                joinArray(root.path("recommended_actions")),
                newsAnalysis,
                text(root, "updated_memory")
        );
    }

    private ReviewAiResponse localReviewFallback(Stock stock, List<NewsItem> newsItems, String reason) {
        List<ReviewNewsAnalysisAiResponse> analysis = new ArrayList<>();
        for (NewsItem item : newsItems) {
            analysis.add(new ReviewNewsAnalysisAiResponse(
                    String.valueOf(item.getId()),
                    "AI provider failed; local fallback did not identify a thesis-breaking trigger.",
                    "NOISE"
            ));
        }
        // Null updatedMemory keeps the stored monitoring memory unchanged.
        return new ReviewAiResponse(
                ThesisChangeLevel.No_Change,
                "AI provider failed for " + stock.getTicker() + "; local fallback defaulted to No Change.",
                reason + " Review manually if today's news looks material.",
                "Keep current status and rerun review with a stronger model if needed.",
                analysis,
                null
        );
    }

    private JsonNode parseTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            if (isLikelyTruncatedJson(ex)) {
                throw new TruncatedJsonException("OpenRouter returned incomplete JSON: " + ex.getOriginalMessage());
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to parse OpenRouter response: " + ex.getMessage());
        }
    }

    private boolean isLikelyTruncatedJson(JsonProcessingException ex) {
        String message = ex.getOriginalMessage();
        return message != null && (message.contains("Unexpected end-of-input")
                || message.contains("end-of-input")
                || message.contains("Unexpected EOF"));
    }

    private String buildThesisUserPrompt(Stock stock, String fundamentalsJson) {
        return fill(buyThesisUserTemplate,
                "{{ticker}}", stock.getTicker(),
                "{{companyName}}", stock.getCompanyName(),
                "{{userNotes}}", NO_DATA,
                "{{fundamentalsJson}}", fundamentalsJson);
    }

    private FundamentalsSnapshot fetchFundamentalsSafe(Stock stock) {
        try {
            return openBbClient.fetchFundamentals(stock.getProviderTicker());
        } catch (Exception ex) {
            log.debug("[OpenRouter] Fundamentals fetch failed for {}: {}", stock.getTicker(), ex.getMessage());
            return null;
        }
    }

    // Best-effort real fundamentals for the growth forecast. Returns NO_DATA when OpenBB is
    // unavailable/rate-limited so the prompt falls back to a low-confidence estimate.
    private String buildFundamentalsJson(Stock stock, FundamentalsSnapshot f) {
        if (f == null) {
            return NO_DATA;
        }
        ObjectNode node = objectMapper.createObjectNode();
        // P/S is the forecast's key input; yfinance often omits it, so derive marketCap/revenue.
        Double ps = f.effectivePriceToSales();
        if (ps != null) node.put("currentPriceToSales", round(ps, 2));
        if (f.peRatio() != null) node.put("peRatio", round(f.peRatio(), 2));
        if (f.marketCap() != null) node.put("marketCapUsd", f.marketCap());
        if (f.latestRevenue() != null) node.put("latestRevenueUsd", f.latestRevenue());
        Double growth = f.historicalRevenueGrowthPct();
        if (growth != null) node.put("historicalRevenueGrowthPct", round(growth, 1));
        if (node.size() == 0) {
            return NO_DATA;
        }
        // Logged so the OpenBB field mapping can be confirmed during the live test.
        log.debug("[OpenRouter] Fundamentals for {}: {}", stock.getTicker(), node);
        return node.toString();
    }

    private double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    // Enforce the law-of-large-numbers ceiling on the forecast bucket: no company has 5x'd from a
    // >$1T base in 7 years. Clamps deterministically when the model overshoots a mega-cap's size.
    private ThesisAiResponse clampForecastToSize(ThesisAiResponse r, FundamentalsSnapshot f) {
        log.debug("[OpenRouter] clampForecastToSize: bucket={}, marketCap={}",
                r == null ? null : r.returnMultiple(), f == null ? null : f.marketCap());
        if (r == null || f == null || f.marketCap() == null
                || r.returnMultiple() == null || r.returnMultiple().isBlank()) {
            return r;
        }
        int proposed = RETURN_BUCKETS.indexOf(r.returnMultiple().trim());
        if (proposed < 0) {
            return r; // unrecognized label; leave the model's value alone
        }
        int ceiling = sizeCeilingIndex(f.marketCap());
        if (proposed <= ceiling) {
            return r; // already within reality for this size
        }
        String capped = RETURN_BUCKETS.get(ceiling);
        String note = "Size-capped to " + capped + " (market cap $" + round(f.marketCap() / 1e12, 2)
                + "T; companies this large rarely exceed it over 7 years).";
        String basis = r.returnBasis() == null || r.returnBasis().isBlank() ? note : r.returnBasis() + "\n" + note;
        log.debug("[OpenRouter] Forecast {} -> {} by size cap (marketCap={})", r.returnMultiple(), capped, f.marketCap());
        return new ThesisAiResponse(
                r.fullBuyThesis(), r.savedBuyThesisSummary(), r.finalRating(), r.conviction(), r.portfolioRole(),
                r.coreThesis(), r.businessEssence(), r.growthDrivers(), r.moatSummary(), r.financialQuality(),
                r.valuationView(), r.mainRisks(), r.thesisBreakTriggers(), r.dailyReviewFocus(),
                capped, basis);
    }

    private int sizeCeilingIndex(double marketCap) {
        if (marketCap > 3e12) return 0;     // > $3T  -> "2x"
        if (marketCap > 1e12) return 1;     // $1-3T  -> "3-5x"
        if (marketCap > 250e9) return 2;    // $250B-1T -> "5-10x"
        return 3;                            // < $250B -> "10x+"
    }

    private String buildReviewUserPrompt(Stock stock, StockThesis thesis, List<NewsItem> newsItems, String monitorMemory) {
        return fill(dailyReviewUserTemplate,
                "{{ticker}}", stock.getTicker(),
                "{{companyName}}", stock.getCompanyName(),
                "{{currentStatus}}", stock.getStatus().getLabel(),
                "{{reviewDate}}", LocalDate.now().toString(),
                "{{thesisJson}}", buildThesisJson(thesis),
                "{{monitorMemoryBlock}}", monitorMemory,
                "{{newsItemsBlock}}", buildNewsItemsBlock(newsItems, REVIEW_SUMMARY_LEN, true));
    }

    // Triage only needs the gist (short snippet, keeps the all-items pass cheap); the expensive
    // doctrine review reads the full article so it reasons over complete content.
    private static final int TRIAGE_SUMMARY_LEN = 400;
    private static final int REVIEW_SUMMARY_LEN = 6000;

    private String buildNewsItemsBlock(List<NewsItem> newsItems, int maxLen, boolean fullContent) {
        StringBuilder news = new StringBuilder();
        for (NewsItem item : newsItems) {
            news.append("[%s] (ThesisGuard, %s) %s%n".formatted(item.getId(), item.getPublishedDate(), item.getTitle()));
            // Review reads the full article (content) for complete context; triage uses the short blurb.
            String text = fullContent && item.getContent() != null ? item.getContent() : item.getSummary();
            if (text != null) {
                news.append(truncate(text, maxLen)).append("\n");
            }
            news.append("---\n");
        }
        return news.toString();
    }

    private String buildThesisJson(StockThesis thesis) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("finalRating", thesis.getFinalRating());
        root.put("conviction", thesis.getConviction());
        root.put("thesisSummary", truncate(thesis.getSavedBuyThesisSummary(), 600));
        root.put("coreThesis", truncate(thesis.getCoreThesis(), 400));
        root.set("killCriteria", textArray(thesis.getThesisBreakTriggers()));
        root.set("watchItems", textArray(thesis.getDailyReviewFocus()));
        return pretty(root);
    }

    private String buildCompactThesisText(StockThesis thesis) {
        return "rating=" + thesis.getFinalRating()
                + "; summary=" + truncate(thesis.getSavedBuyThesisSummary(), 240)
                + "; kill=" + truncate(thesis.getThesisBreakTriggers(), 300)
                + "; watch=" + truncate(thesis.getDailyReviewFocus(), 240);
    }

    private ArrayNode textArray(String value) {
        ArrayNode array = objectMapper.createArrayNode();
        if (value == null || value.isBlank()) {
            return array;
        }
        for (String line : value.split("\\R")) {
            String cleaned = line.replaceFirst("^\\s*\\d+[.)]\\s*", "").trim();
            if (!cleaned.isBlank()) {
                array.add(cleaned);
            }
        }
        return array;
    }

    private String fill(String template, String... pairs) {
        String result = template;
        for (int i = 0; i < pairs.length; i += 2) {
            String value = pairs[i + 1];
            result = result.replace(pairs[i], value == null || value.isBlank() ? NO_DATA : value);
        }
        return result;
    }

    private String pretty(JsonNode node) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to format AI response: " + ex.getMessage());
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = field.isEmpty() ? node : node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        return value.asText("");
    }

    private String joinArray(JsonNode node) {
        if (!node.isArray()) {
            return text(node, "");
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            if (!item.isNull() && !item.asText("").isBlank()) {
                values.add(item.asText());
            }
        });
        return String.join("\n", values);
    }

    private String joinNonBlank(String... values) {
        List<String> nonBlank = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank() && !value.endsWith(": ")) {
                nonBlank.add(value);
            }
        }
        return String.join("\n", nonBlank);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= maxLength) {
            return cleaned;
        }
        return cleaned.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private ThesisChangeLevel mapChangeLevel(String changeLevel) {
        return switch (changeLevel == null ? "" : changeLevel.toUpperCase()) {
            case "NONE", "NOISE" -> ThesisChangeLevel.No_Change;
            case "MINOR" -> ThesisChangeLevel.Minor_Change;
            case "MAJOR" -> ThesisChangeLevel.Material_Change;
            case "CRITICAL" -> ThesisChangeLevel.Thesis_Broken;
            default -> ThesisChangeLevel.No_Change;
        };
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record OpenRouterRequest(
            String model,
            List<RequestMessage> messages,
            @JsonProperty("response_format") ResponseFormat responseFormat,
            Double temperature,
            @JsonProperty("max_tokens") Integer maxTokens,
            ReasoningConfig reasoning,
            ProviderConfig provider
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReasoningConfig(String effort, Boolean exclude) {}

    // response_format: either {"type":"json_object"} or strict structured outputs
    // {"type":"json_schema","json_schema":{...}}. json_schema is omitted for json_object.
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ResponseFormat(String type, @JsonProperty("json_schema") JsonSchemaSpec jsonSchema) {
        ResponseFormat(String type) {
            this(type, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JsonSchemaSpec(String name, Boolean strict, JsonNode schema) {}

    // Forces OpenRouter to only route to providers that honor the request's parameters
    // (e.g. structured outputs), so an unsupported model errors loudly instead of degrading.
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProviderConfig(@JsonProperty("require_parameters") Boolean requireParameters) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RequestMessage(String role, String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResponseMessage(String role, JsonNode content, String reasoning, String refusal) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenRouterResponse(List<Choice> choices, OpenRouterError error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Choice(
            ResponseMessage message,
            @JsonProperty("finish_reason") String finishReason,
            @JsonProperty("native_finish_reason") String nativeFinishReason,
            OpenRouterError error
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenRouterError(String message) {}

    private static class EmptyMessageContentException extends ApiException {
        EmptyMessageContentException(String message) {
            super(HttpStatus.BAD_GATEWAY, message);
        }
    }

    private static class TruncatedJsonException extends ApiException {
        TruncatedJsonException(String message) {
            super(HttpStatus.BAD_GATEWAY, message);
        }
    }
}
