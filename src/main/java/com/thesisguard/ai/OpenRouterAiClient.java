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
import com.thesisguard.news.NewsItem;
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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(name = "openrouter.api-key")
public class OpenRouterAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterAiClient.class);
    private static final String BASE_URL = "https://openrouter.ai/api/v1";
    private static final String NO_DATA = "(none)";
    private static final String COMPACT_REVIEW_SYSTEM_PROMPT = """
            You are a conservative investment thesis news reviewer.
            Default to NOISE unless news clearly matches a thesis-breaking trigger.
            Return only the requested minified JSON.
            """;
    private static final Pattern CODE_BLOCK = Pattern.compile("(?s)```[a-zA-Z]*\\n?(\\{.*?\\})\\n?```");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String thesisModel;
    private final String reviewModel;
    private final String buyThesisSystemPrompt;
    private final String buyThesisUserTemplate;
    private final String dailyReviewSystemPrompt;
    private final String dailyReviewUserTemplate;

    public OpenRouterAiClient(OpenRouterProperties properties, ObjectMapper objectMapper) {
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Authorization", "Bearer " + properties.apiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.objectMapper = objectMapper;
        this.thesisModel = properties.model() != null ? properties.model() : "google/gemini-2.0-flash-exp:free";
        this.reviewModel = properties.reviewModel() != null ? properties.reviewModel() : this.thesisModel;
        this.buyThesisSystemPrompt = loadPrompt("prompts/buy_thesis_system_prompt.txt");
        this.buyThesisUserTemplate = loadPrompt("prompts/buy_thesis_user_template.txt");
        this.dailyReviewSystemPrompt = loadPrompt("prompts/daily_review_system_prompt.txt");
        this.dailyReviewUserTemplate = loadPrompt("prompts/daily_review_user_template.txt");
    }

    @Override
    public ThesisAiResponse generateBuyThesis(Stock stock) {
        String json = callOpenRouter(thesisModel, buyThesisSystemPrompt, buildThesisUserPrompt(stock), 0.4, 6000);
        try {
            return mapThesisResponse(json);
        } catch (TruncatedJsonException ex) {
            log.debug("[OpenRouter] Thesis JSON was truncated; retrying with compact fallback schema");
            String compactJson = callOpenRouter(thesisModel, buyThesisSystemPrompt, buildCompactThesisUserPrompt(stock), 0.2, 2000);
            try {
                return mapCompactThesisResponse(compactJson, stock);
            } catch (TruncatedJsonException compactEx) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "OpenRouter model output was truncated even with the compact fallback schema. Switch to a model with a larger completion limit.");
            }
        }
    }

    @Override
    public ReviewAiResponse reviewNews(Stock stock, StockThesis thesis, List<NewsItem> newsItems) {
        try {
            String json = callOpenRouter(reviewModel, dailyReviewSystemPrompt, buildReviewUserPrompt(stock, thesis, newsItems), 0.1, reviewMaxTokens(newsItems.size()));
            return mapReviewResponse(json);
        } catch (TruncatedJsonException ex) {
            log.debug("[OpenRouter] Review JSON was truncated; retrying with compact fallback schema");
            try {
                String compactJson = callOpenRouter(reviewModel, COMPACT_REVIEW_SYSTEM_PROMPT, buildCompactReviewUserPrompt(stock, thesis, newsItems), 0.0, 4000);
                return mapCompactReviewResponse(compactJson);
            } catch (TruncatedJsonException compactEx) {
                log.debug("[OpenRouter] Compact review fallback was also truncated; using local conservative fallback");
                return localReviewFallback(stock, newsItems, "OpenRouter output was truncated before valid JSON completed.");
            } catch (EmptyMessageContentException compactEx) {
                log.debug("[OpenRouter] Compact review fallback returned empty content; using local conservative fallback");
                return localReviewFallback(stock, newsItems, "OpenRouter returned empty content.");
            }
        }
    }

    private int reviewMaxTokens(int newsCount) {
        // Reasoning models spend hidden thinking tokens out of the same completion
        // budget, and itemAnalyses grows with every saved article, so a flat limit
        // truncates the JSON once either grows.
        return Math.min(16000, 6000 + 250 * newsCount);
    }

    private String callOpenRouter(String model, String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        log.debug("[OpenRouter] Sending system prompt ({} chars) and user prompt ({} chars) to model '{}'",
                systemPrompt.length(), userPrompt.length(), model);
        try {
            OpenRouterRequest plainRequest = buildRequest(model, systemPrompt, userPrompt, temperature, maxTokens, null);
            OpenRouterResponse response = executeRequest(plainRequest);
            String text;
            try {
                text = extractText(response);
            } catch (EmptyMessageContentException ex) {
                log.debug("[OpenRouter] Empty content without response_format; retrying with response_format=json_object");
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
        return new OpenRouterRequest(
                model,
                List.of(new RequestMessage("system", systemPrompt), new RequestMessage("user", userPrompt)),
                responseFormat,
                temperature,
                maxTokens,
                new ReasoningConfig("none", true)
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

    private ThesisAiResponse mapThesisResponse(String json) {
        JsonNode root = parseTree(json);
        JsonNode firstPrinciples = root.path("firstPrinciples");
        JsonNode trend = root.path("trend");
        JsonNode fiveCriteria = root.path("fiveCriteria");
        JsonNode positionGuidance = root.path("positionGuidance");

        return new ThesisAiResponse(
                pretty(root),
                text(root, "thesisSummary"),
                text(root, "verdict"),
                convictionFromScore(root.path("convictionScore").asInt(0)),
                text(root, "companyType"),
                text(root, "thesisSummary"),
                joinNonBlank(
                        "Industry: " + text(firstPrinciples, "trueIndustry"),
                        "Winning rules: " + text(firstPrinciples, "industryWinningRules"),
                        "Core advantage: " + text(firstPrinciples, "coreAdvantage"),
                        "Direction: " + text(firstPrinciples, "coreAdvantageDirection")
                ),
                joinNonBlank(
                        "Mega-trend: " + text(trend, "megaTrend"),
                        "Trend stage: " + text(trend, "stage"),
                        "Stage rationale: " + text(trend, "stageRationale"),
                        "Market size: " + text(fiveCriteria.path("marketSize"), "evidence")
                ),
                joinNonBlank(
                        "Score: " + fiveCriteria.path("moat").path("score").asText("0"),
                        "Types: " + joinArray(fiveCriteria.path("moat").path("moatTypes")),
                        "Depth: " + text(fiveCriteria.path("moat"), "moatDepth")
                ),
                joinNonBlank(
                        "Rapid growth: " + text(fiveCriteria.path("rapidGrowth"), "evidence"),
                        "Rule of 40: " + fiveCriteria.path("rapidGrowth").path("ruleOf40Value").asText("0"),
                        "Cash flow: " + text(fiveCriteria.path("cashFlow"), "evidence")
                ),
                joinNonBlank(
                        "Entry strategy: " + text(positionGuidance, "entryStrategy"),
                        "Max portfolio percent: " + positionGuidance.path("maxPortfolioPercent").asText("0"),
                        "Return target: " + text(positionGuidance, "returnTarget")
                ),
                joinNonBlank(
                        "Paradigm shift threat: " + text(firstPrinciples.path("paradigmShiftRisk"), "threat"),
                        "Probability: " + text(firstPrinciples.path("paradigmShiftRisk"), "probability"),
                        "Failed gates: " + joinArray(root.path("exclusionGates").path("failedGates"))
                ),
                joinArray(root.path("killCriteria")),
                joinArray(root.path("watchItems"))
        );
    }

    private ThesisAiResponse mapCompactThesisResponse(String json, Stock stock) {
        JsonNode root = parseTree(json);
        String verdict = text(root, "verdict");
        int convictionScore = root.path("convictionScore").asInt(0);
        String thesisSummary = text(root, "thesisSummary");
        String killCriteria = joinArray(root.path("killCriteria"));
        String watchItems = joinArray(root.path("watchItems"));
        String risks = joinArray(root.path("risks"));

        return new ThesisAiResponse(
                pretty(root),
                thesisSummary,
                verdict,
                convictionFromScore(convictionScore),
                text(root, "companyType"),
                thesisSummary,
                "Core advantage: " + text(root, "coreAdvantage") + "\nDirection: " + text(root, "coreAdvantageDirection"),
                text(root, "trend"),
                text(root, "moat"),
                text(root, "financialQuality"),
                text(root, "valuationView"),
                risks,
                killCriteria,
                watchItems.isBlank() ? "Monitor quarterly fundamentals, moat signals, valuation, and thesis-breaking events for " + stock.getTicker() + "." : watchItems
        );
    }

    private ReviewAiResponse mapReviewResponse(String json) {
        JsonNode root = parseTree(json);
        List<ReviewNewsAnalysisAiResponse> newsAnalysis = new ArrayList<>();
        root.path("itemAnalyses").forEach(item -> newsAnalysis.add(new ReviewNewsAnalysisAiResponse(
                text(item, "newsItemId"),
                text(item, "analysis"),
                text(item, "itemChangeLevel")
        )));

        String thesisImpact = text(root, "thesisImpactSummary");
        String newsSummary = text(root, "newsSummary");
        return new ReviewAiResponse(
                mapChangeLevel(text(root, "changeLevel")),
                newsSummary.isBlank() ? thesisImpact : newsSummary,
                thesisImpact,
                joinArray(root.path("recommendedActions")),
                newsAnalysis
        );
    }

    private ReviewAiResponse mapCompactReviewResponse(String json) {
        JsonNode root = parseTree(json);
        List<ReviewNewsAnalysisAiResponse> newsAnalysis = new ArrayList<>();
        root.path("items").forEach(item -> newsAnalysis.add(new ReviewNewsAnalysisAiResponse(
                text(item, "id"),
                text(item, "analysis"),
                text(item, "level")
        )));

        return new ReviewAiResponse(
                mapChangeLevel(text(root, "changeLevel")),
                text(root, "summary"),
                text(root, "impact"),
                text(root, "action"),
                newsAnalysis
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
        return new ReviewAiResponse(
                ThesisChangeLevel.No_Change,
                "AI provider failed for " + stock.getTicker() + "; local fallback defaulted to No Change.",
                reason + " Review manually if today's news looks material.",
                "Keep current status and rerun review with a stronger model if needed.",
                analysis
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

    private String buildThesisUserPrompt(Stock stock) {
        return fill(buyThesisUserTemplate,
                "{{ticker}}", stock.getTicker(),
                "{{companyName}}", stock.getCompanyName(),
                "{{userNotes}}", NO_DATA,
                "{{fundamentalsJson}}", NO_DATA);
    }

    private String buildCompactThesisUserPrompt(Stock stock) {
        return """
                Generate a compact long-term buy thesis for this stock using the system doctrine.
                Ticker: %s
                Company: %s

                Return ONLY minified JSON. No markdown. Every string under 90 characters.
                Required schema:
                {"verdict":"GREEN|YELLOW|RED","convictionScore":0,"companyType":"TECH_INNOVATOR|BUSINESS_MODEL_INNOVATOR|BRAND|ECOSYSTEM","trend":"string","coreAdvantage":"string","coreAdvantageDirection":"STRENGTHENING|STABLE|WEAKENING","moat":"string","financialQuality":"string","valuationView":"string","thesisSummary":"string","killCriteria":["string","string","string"],"watchItems":["string","string","string"],"risks":["string","string","string"]}
                """.formatted(stock.getTicker(), stock.getCompanyName());
    }

    private String buildReviewUserPrompt(Stock stock, StockThesis thesis, List<NewsItem> newsItems) {
        return fill(dailyReviewUserTemplate,
                "{{ticker}}", stock.getTicker(),
                "{{companyName}}", stock.getCompanyName(),
                "{{currentStatus}}", stock.getStatus().getLabel(),
                "{{reviewDate}}", LocalDate.now().toString(),
                "{{thesisJson}}", buildThesisJson(thesis),
                "{{newsItemsBlock}}", buildNewsItemsBlock(newsItems));
    }

    private String buildCompactReviewUserPrompt(Stock stock, StockThesis thesis, List<NewsItem> newsItems) {
        return """
                Review today's news against this saved long-term thesis.
                Stock: %s -- %s
                Status: %s
                Date: %s
                Thesis: %s
                News: %s

                Return ONLY minified JSON. No markdown. Every string under 90 characters.
                Schema: {"changeLevel":"NONE|NOISE|MINOR|MAJOR|CRITICAL","summary":"string","impact":"string","action":"string","items":[{"id":"string","level":"NONE|NOISE|MINOR|MAJOR|CRITICAL","analysis":"string"}]}
                """.formatted(
                stock.getTicker(),
                stock.getCompanyName(),
                stock.getStatus().getLabel(),
                LocalDate.now(),
                buildCompactThesisText(thesis),
                buildCompactNewsText(newsItems)
        );
    }

    private String buildNewsItemsBlock(List<NewsItem> newsItems) {
        StringBuilder news = new StringBuilder();
        for (NewsItem item : newsItems) {
            news.append("[%s] (ThesisGuard, %s) %s%n".formatted(item.getId(), item.getPublishedDate(), item.getTitle()));
            if (item.getSummary() != null) {
                news.append(truncate(item.getSummary(), 400)).append("\n");
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

    private String buildCompactNewsText(List<NewsItem> newsItems) {
        StringBuilder news = new StringBuilder();
        int count = 0;
        for (NewsItem item : newsItems) {
            if (count >= 8) {
                news.append("more_news_omitted");
                break;
            }
            news.append("[%s] %s - %s; ".formatted(
                    item.getId(),
                    truncate(item.getTitle(), 120),
                    truncate(item.getSummary(), 180)
            ));
            count++;
        }
        return news.toString();
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

    private String convictionFromScore(int score) {
        if (score >= 70) {
            return "High";
        }
        if (score >= 40) {
            return "Medium";
        }
        return "Low";
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
            ReasoningConfig reasoning
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReasoningConfig(String effort, Boolean exclude) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResponseFormat(String type) {}

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
