# Flatten Thesis & Review AI Output Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the cheap demo model emit the flat thesis/review fields directly so no UI panel is blank and generation is faster.

**Architecture:** Keep the 6-stage thesis doctrine and K1–K4 review doctrine as *reasoning instructions* in the system prompts, but reshape the model's enforced JSON output to a **flat schema that maps 1:1 onto the existing `ThesisAiResponse` / `ReviewAiResponse` records**. The brittle nested→flat string-stitching and the compact fallbacks are deleted; mapping becomes direct field reads.

**Tech Stack:** Java 21, Spring Boot, Jackson, OpenRouter structured outputs (`response_format: json_schema`, strict), JUnit 5.

## Global Constraints

- No frontend changes, no DB/entity changes — `StockThesis`, `StockThesisResponse`, `ThesisAiResponse`, `ReviewAiResponse`, `ReviewNewsAnalysisAiResponse` record shapes stay **unchanged** (they are the contract).
- `MockAiClient` is unchanged; the existing test suite uses it and must stay green.
- `clampForecastToSize` (size cap) and the triage gate (`triageNews`) are **unchanged**.
- All new JSON schemas use `"additionalProperties": false` and list every property in `"required"` (strict structured outputs).
- `ThesisAiResponse` field order (constructor): `fullBuyThesis, savedBuyThesisSummary, finalRating, conviction, portfolioRole, coreThesis, businessEssence, growthDrivers, moatSummary, financialQuality, valuationView, mainRisks, thesisBreakTriggers, dailyReviewFocus, returnMultiple, returnBasis`.
- `ReviewAiResponse` field order: `thesisChangeLevel, summary, thesisImpact, recommendedAction, newsAnalysis, updatedMemory`.
- `ReviewNewsAnalysisAiResponse` field order: `newsItemId, analysis, impactLevel`.

---

## File Structure

| File | Responsibility | Change |
| --- | --- | --- |
| `src/main/resources/prompts/buy_thesis_schema.json` | Enforced thesis output shape | Rewrite → flat |
| `src/main/resources/prompts/buy_thesis_system_prompt.txt` | Thesis doctrine + output instructions | Two targeted edits |
| `src/main/resources/prompts/buy_thesis_user_template.txt` | Thesis per-request field guidance | Rewrite |
| `src/main/resources/prompts/review_schema.json` | Enforced review output shape | **New** |
| `src/main/resources/prompts/daily_review_user_template.txt` | Review per-request field guidance | Rewrite |
| `src/main/java/com/thesisguard/ai/OpenRouterAiClient.java` | Calls + mappings + fallbacks | Rewrite mappings, drop compact paths, add review schema |
| `src/test/java/com/thesisguard/ai/OpenRouterMappingTest.java` | Unit-test the flat mappings | **New** |

Mapping methods `mapThesisResponse` / `mapReviewResponse` become **package-private** so the test in `com.thesisguard.ai` can call them on a directly-constructed client. The client can be constructed offline: its constructor only builds a `RestClient` and loads classpath prompt/schema resources — it never calls the network or `OpenBbClient`, so `openBbClient` may be `null` in the test.

---

## Task 1: Thesis output → flat schema + direct mapping

**Files:**
- Modify: `src/main/resources/prompts/buy_thesis_schema.json` (full rewrite)
- Modify: `src/main/resources/prompts/buy_thesis_system_prompt.txt` (two edits)
- Modify: `src/main/resources/prompts/buy_thesis_user_template.txt` (full rewrite)
- Modify: `src/main/java/com/thesisguard/ai/OpenRouterAiClient.java` (`generateBuyThesis`, `mapThesisResponse`; delete `mapCompactThesisResponse`, `buildCompactThesisUserPrompt`, `buildReturnBasis`, `convictionFromScore`)
- Test: `src/test/java/com/thesisguard/ai/OpenRouterMappingTest.java` (new)

**Interfaces:**
- Consumes: existing helpers in `OpenRouterAiClient` — `parseTree(String)→JsonNode`, `text(JsonNode,String)→String`, `joinArray(JsonNode)→String` (newline-joins non-blank array elements), `clampForecastToSize(ThesisAiResponse,FundamentalsSnapshot)`, `callOpenRouter(model,system,user,temp,maxTokens,ResponseFormat)`, field `buyThesisResponseFormat`.
- Produces: package-private `ThesisAiResponse mapThesisResponse(String json)` reading the flat fields. New constructor usage `new OpenRouterAiClient(OpenRouterProperties, ObjectMapper, OpenBbClient)` is unchanged (3 args).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/thesisguard/ai/OpenRouterMappingTest.java`:

```java
package com.thesisguard.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thesisguard.thesis.ThesisAiResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenRouterMappingTest {

    private final OpenRouterAiClient client = new OpenRouterAiClient(
            new OpenRouterProperties("test-key", "m", "rm", "tm", "nm"),
            new ObjectMapper(),
            null);

    @Test
    void mapsFlatThesisJsonToEveryField() {
        String json = """
            {
              "final_rating": "GREEN",
              "conviction": "High",
              "portfolio_role": "Core growth holding",
              "saved_buy_thesis_summary": "Compounds on AI compute demand.",
              "core_thesis": "Owns the AI accelerator stack.",
              "business_essence": "Designs GPUs and the CUDA software moat.",
              "growth_drivers": "AI training and inference buildout.",
              "moat_summary": "CUDA ecosystem, scale, switching costs.",
              "financial_quality": "70%+ margins, strong free cash flow.",
              "valuation_view": "Rich, but justified by durable growth.",
              "main_risks": "Custom-silicon substitution by hyperscalers.",
              "thesis_break_triggers": ["Rival ships CUDA-free production stack", "Hyperscaler ASIC takes majority share"],
              "daily_review_focus": ["Data-center revenue growth", "Gross margin trend"],
              "return_multiple": "3-5x",
              "return_basis": "CAGR 25%, terminal P/S 12x, bear 2x, bull 5-10x, HIGH",
              "full_buy_thesis": "Paragraph one.\\n\\nParagraph two."
            }
            """;

        ThesisAiResponse r = client.mapThesisResponse(json);

        assertThat(r.finalRating()).isEqualTo("GREEN");
        assertThat(r.conviction()).isEqualTo("High");
        assertThat(r.portfolioRole()).isEqualTo("Core growth holding");
        assertThat(r.savedBuyThesisSummary()).isEqualTo("Compounds on AI compute demand.");
        assertThat(r.coreThesis()).isEqualTo("Owns the AI accelerator stack.");
        assertThat(r.businessEssence()).contains("CUDA software moat");
        assertThat(r.growthDrivers()).contains("inference buildout");
        assertThat(r.moatSummary()).contains("switching costs");
        assertThat(r.financialQuality()).contains("free cash flow");
        assertThat(r.valuationView()).contains("durable growth");
        assertThat(r.mainRisks()).contains("Custom-silicon");
        assertThat(r.thesisBreakTriggers()).isEqualTo("Rival ships CUDA-free production stack\nHyperscaler ASIC takes majority share");
        assertThat(r.dailyReviewFocus()).isEqualTo("Data-center revenue growth\nGross margin trend");
        assertThat(r.returnMultiple()).isEqualTo("3-5x");
        assertThat(r.returnBasis()).contains("terminal P/S 12x");
        assertThat(r.fullBuyThesis()).contains("Paragraph two.");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=OpenRouterMappingTest`
Expected: FAIL — the current nested `mapThesisResponse` reads keys like `thesisSummary`/`firstPrinciples` that aren't present, so `finalRating()` comes back as the `verdict` value `""` and most assertions fail. (`mapThesisResponse` must already be visible to the test; if it is still `private`, this is a compile error — that is also a valid "fail", fixed in Step 6.)

- [ ] **Step 3: Replace the thesis schema with the flat shape**

Overwrite `src/main/resources/prompts/buy_thesis_schema.json` with:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "final_rating", "conviction", "portfolio_role", "saved_buy_thesis_summary",
    "core_thesis", "business_essence", "growth_drivers", "moat_summary",
    "financial_quality", "valuation_view", "main_risks", "thesis_break_triggers",
    "daily_review_focus", "return_multiple", "return_basis", "full_buy_thesis"
  ],
  "properties": {
    "final_rating": { "type": "string", "enum": ["GREEN", "YELLOW", "RED"] },
    "conviction": { "type": "string", "enum": ["High", "Medium", "Low"] },
    "portfolio_role": { "type": "string" },
    "saved_buy_thesis_summary": { "type": "string" },
    "core_thesis": { "type": "string" },
    "business_essence": { "type": "string" },
    "growth_drivers": { "type": "string" },
    "moat_summary": { "type": "string" },
    "financial_quality": { "type": "string" },
    "valuation_view": { "type": "string" },
    "main_risks": { "type": "string" },
    "thesis_break_triggers": { "type": "array", "items": { "type": "string" }, "minItems": 3, "maxItems": 7 },
    "daily_review_focus": { "type": "array", "items": { "type": "string" }, "minItems": 3, "maxItems": 6 },
    "return_multiple": { "type": "string", "enum": ["2x", "3-5x", "5-10x", "10x+"] },
    "return_basis": { "type": "string" },
    "full_buy_thesis": { "type": "string" }
  }
}
```

- [ ] **Step 4: Update the two schema-coupled sections of the system prompt**

In `src/main/resources/prompts/buy_thesis_system_prompt.txt`:

Edit A — replace this block:

```
REQUIRED OUTPUTS FOR DOWNSTREAM MONITORING:
- killCriteria: 3-7 specific, OBSERVABLE conditions that would break this thesis. Each must be concrete enough that a daily news review can match headlines against it. Good example: "A competitor ships a production GPU that runs mainstream AI frameworks without the incumbent's software ecosystem at materially lower cost." Bad example: "Competition increases."
- watchItems: specific metrics and events to monitor quarterly (growth rate thresholds, margin trends, named competitor products, regulatory decisions).
```

with:

```
REQUIRED OUTPUTS FOR DOWNSTREAM MONITORING:
- thesis_break_triggers: 3-7 specific, OBSERVABLE conditions that would break this thesis. Each must be concrete enough that a daily news review can match headlines against it. Good example: "A competitor ships a production GPU that runs mainstream AI frameworks without the incumbent's software ecosystem at materially lower cost." Bad example: "Competition increases."
- daily_review_focus: specific metrics and events to monitor quarterly (growth rate thresholds, margin trends, named competitor products, regulatory decisions).
```

Edit B — replace this line (Stage 6 closer):

```
Output all of this in the growthForecast object defined in the user message. Be honest: most stocks land in "2x" or "3-5x"; large-cap leaders rarely beat "3-5x"; "10x+" is reserved for small, early names with a strong real trend, large TAM, multiple deep moats, and durable 35%+ growth.
```

with:

```
Put the resulting bucket in return_multiple and your one-line derivation (CAGR, terminal multiple, bear/bull case, confidence) in return_basis. Be honest: most stocks land in "2x" or "3-5x"; large-cap leaders rarely beat "3-5x"; "10x+" is reserved for small, early names with a strong real trend, large TAM, multiple deep moats, and durable 35%+ growth.
```

- [ ] **Step 5: Rewrite the thesis user template to the flat fields**

Overwrite `src/main/resources/prompts/buy_thesis_user_template.txt` with:

```
Generate a long-term buy thesis for the following stock.

Ticker: {{ticker}}
Company: {{companyName}}
Analyst notes / context (may be empty): {{userNotes}}
Latest fundamentals from data provider (may be empty): {{fundamentalsJson}}

Work through the full Stage 0-6 doctrine in your reasoning, but RETURN ONLY the flat JSON
object enforced by the schema. Every field is required; never leave one blank. If evidence is
thin, write a brief honest note (e.g. "insufficient data; based on general knowledge") rather
than an empty string. Keep each string concise.

Field guidance:
- final_rating: GREEN, YELLOW, or RED per Stage 5. The default is NOT GREEN.
- conviction: High, Medium, or Low (GREEN typically High, YELLOW Medium, RED Low).
- portfolio_role: the company type and how it would serve a concentrated growth portfolio.
- saved_buy_thesis_summary: 3-5 plain sentences on why this compounds for 5-7 years.
- core_thesis: one or two sentences capturing the single central bet.
- business_essence: what the business really does and the industry it really competes in.
- growth_drivers: the mega-trend, its stage, and the concrete drivers of 5-7 year growth.
- moat_summary: the moats present (technology, network, scale, brand, switching cost, data,
  regulatory, distribution, talent) and how deep and durable they are.
- financial_quality: growth rate, Rule of 40, margins, and cash-flow self-funding.
- valuation_view: an honest read on the current valuation versus the growth it must deliver.
- main_risks: the most credible threats, including the single biggest paradigm-shift risk.
- thesis_break_triggers: 3-7 OBSERVABLE, headline-matchable conditions that would BREAK the
  thesis (consumed by the daily review). Bad: "competition increases". Good: "a rival ships a
  production product that runs mainstream workloads without our ecosystem at lower cost".
- daily_review_focus: 3-6 specific metrics/events to monitor (growth thresholds, margin trends,
  named competitor products, regulatory decisions).
- return_multiple: follow Stage 6 (2x, 3-5x, 5-10x, or 10x+) after applying the size cap.
- return_basis: one line - CAGR assumption, terminal multiple, bear/bull case, and confidence.
- full_buy_thesis: a 2-4 paragraph prose narrative tying the above together for a reader.
```

- [ ] **Step 6: Rewrite `mapThesisResponse`, simplify `generateBuyThesis`, delete dead helpers**

In `src/main/java/com/thesisguard/ai/OpenRouterAiClient.java`:

(a) Replace the whole `generateBuyThesis` method body:

```java
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
            String retryJson = callOpenRouter(thesisModel, buyThesisSystemPrompt,
                    buildThesisUserPrompt(stock, fundamentalsJson), 0.2, 12000, buyThesisResponseFormat);
            response = mapThesisResponse(retryJson);
        }
        // The model proposes the forecast bucket; code clamps it to the law-of-large-numbers ceiling.
        return clampForecastToSize(response, fundamentals);
    }
```

(b) Replace the whole `mapThesisResponse` method (and drop `private` so the test can call it):

```java
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
```

(c) Delete these now-unused methods entirely: `mapCompactThesisResponse`, `buildCompactThesisUserPrompt`, `buildReturnBasis`, `convictionFromScore`. (Verify no remaining references with a search before deleting; after this task only the review compact code — removed in Task 2 — should reference anything compact.)

- [ ] **Step 7: Run the mapping test and confirm it passes**

Run: `.\mvnw.cmd test -Dtest=OpenRouterMappingTest`
Expected: PASS.

- [ ] **Step 8: Run the full suite**

Run: `.\mvnw.cmd test`
Expected: PASS (all existing tests use `MockAiClient`, unaffected).

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/prompts/buy_thesis_schema.json \
        src/main/resources/prompts/buy_thesis_system_prompt.txt \
        src/main/resources/prompts/buy_thesis_user_template.txt \
        src/main/java/com/thesisguard/ai/OpenRouterAiClient.java \
        src/test/java/com/thesisguard/ai/OpenRouterMappingTest.java
git commit -m "feat(ai): flatten buy-thesis output schema to fix blank panels"
```

---

## Task 2: Review output → flat schema + direct mapping

**Files:**
- Create: `src/main/resources/prompts/review_schema.json`
- Modify: `src/main/resources/prompts/daily_review_user_template.txt` (full rewrite)
- Modify: `src/main/java/com/thesisguard/ai/OpenRouterAiClient.java` (`reviewNews`, `mapReviewResponse`, add `reviewResponseFormat` + `loadReviewResponseFormat`; delete `mapCompactReviewResponse`, `buildCompactReviewUserPrompt`, `buildCompactNewsText`, `COMPACT_REVIEW_SYSTEM_PROMPT`)
- Test: `src/test/java/com/thesisguard/ai/OpenRouterMappingTest.java` (add a method)

**Interfaces:**
- Consumes: `mapChangeLevel(String)→ThesisChangeLevel` (existing), `parseTree`, `text`, `joinArray`, `loadPrompt(String)`, `callOpenRouter(...,ResponseFormat)`, records `ResponseFormat(String,JsonSchemaSpec)` and `JsonSchemaSpec(String name, Boolean strict, JsonNode schema)`, `localReviewFallback(Stock,List<NewsItem>,String)` (kept), `reviewMaxTokens(int)` (kept).
- Produces: package-private `ReviewAiResponse mapReviewResponse(String json)` reading flat fields; new field `private final ResponseFormat reviewResponseFormat;`.

- [ ] **Step 1: Add the failing review mapping test**

Append this method to `OpenRouterMappingTest` (add `import com.thesisguard.review.ReviewAiResponse;` and `import com.thesisguard.review.ThesisChangeLevel;`):

```java
    @Test
    void mapsFlatReviewJsonToEveryField() {
        String json = """
            {
              "change_level": "MAJOR",
              "news_summary": "Regulator opened a formal probe into the data unit.",
              "thesis_impact": "Probe touches a moat pillar; needs human re-evaluation.",
              "recommended_actions": ["Reduce on strength", "Track probe scope weekly"],
              "item_analyses": [
                {"news_item_id": "11", "item_change_level": "MAJOR", "analysis": "Formal probe, confirmed."},
                {"news_item_id": "12", "item_change_level": "NOISE", "analysis": "Routine analyst downgrade."}
              ],
              "updated_memory": "2026-06-25: probe opened (K2 governance watch)."
            }
            """;

        ReviewAiResponse r = client.mapReviewResponse(json);

        assertThat(r.thesisChangeLevel()).isEqualTo(ThesisChangeLevel.Material_Change);
        assertThat(r.summary()).contains("formal probe");
        assertThat(r.thesisImpact()).contains("re-evaluation");
        assertThat(r.recommendedAction()).isEqualTo("Reduce on strength\nTrack probe scope weekly");
        assertThat(r.newsAnalysis()).hasSize(2);
        assertThat(r.newsAnalysis().get(0).newsItemId()).isEqualTo("11");
        assertThat(r.newsAnalysis().get(0).impactLevel()).isEqualTo("MAJOR");
        assertThat(r.newsAnalysis().get(1).analysis()).contains("downgrade");
        assertThat(r.updatedMemory()).contains("probe opened");
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=OpenRouterMappingTest`
Expected: FAIL — the current `mapReviewResponse` reads `changeLevel`/`newsSummary`/`itemAnalyses`, which the flat JSON does not contain, so `thesisChangeLevel()` defaults to `No_Change` and `newsAnalysis()` is empty. (Compile error if `mapReviewResponse` is still `private` — also a valid fail, fixed in Step 5.)

- [ ] **Step 3: Create the review schema**

Create `src/main/resources/prompts/review_schema.json`:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["change_level", "news_summary", "thesis_impact", "recommended_actions", "item_analyses", "updated_memory"],
  "properties": {
    "change_level": { "type": "string", "enum": ["NONE", "NOISE", "MINOR", "MAJOR", "CRITICAL"] },
    "news_summary": { "type": "string" },
    "thesis_impact": { "type": "string" },
    "recommended_actions": { "type": "array", "items": { "type": "string" } },
    "item_analyses": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["news_item_id", "item_change_level", "analysis"],
        "properties": {
          "news_item_id": { "type": "string" },
          "item_change_level": { "type": "string", "enum": ["NONE", "NOISE", "MINOR", "MAJOR", "CRITICAL"] },
          "analysis": { "type": "string" }
        }
      }
    },
    "updated_memory": { "type": "string" }
  }
}
```

- [ ] **Step 4: Rewrite the review user template to the flat fields**

Overwrite `src/main/resources/prompts/daily_review_user_template.txt` with:

```
Review today's news for the following holding against its saved thesis.

Stock: {{ticker}} -- {{companyName}}
Current status: {{currentStatus}}
Review date: {{reviewDate}}

=== SAVED THESIS (JSON) ===
{{thesisJson}}

=== ACCUMULATED MONITORING NOTES (carried over from prior reviews) ===
{{monitorMemoryBlock}}

=== TODAY'S NEWS ITEMS ===
{{newsItemsBlock}}

(Each news item is formatted as:
[id] (source, publishedAt) headline
summary
---)

Reason through the K1-K4 doctrine internally, but RETURN ONLY the flat JSON object enforced by
the schema. All fields are required.

Field guidance:
- change_level: the MAXIMUM item_change_level across all items (NONE/NOISE/MINOR/MAJOR/CRITICAL).
- news_summary: 1-2 sentences on what today's items reported -- facts only, no thesis judgment.
- thesis_impact: 2-4 sentences on the net effect of today's news on the 5-7 year thesis.
- recommended_actions: concrete next actions; empty array if none.
- item_analyses: exactly one entry per news item, using its id from the [id] prefix as
  news_item_id, plus its item_change_level and a one-sentence analysis (under 160 characters).
- updated_memory: the COMPLETE rewritten monitoring notes the next review will receive in place
  of the notes above. Carry forward every still-relevant entry, fold in only DURABLE new findings
  (ongoing storylines, partially-tripped triggers, recurring patterns), merge entries about the
  same storyline, and drop resolved or pure-noise items. Keep it under 2000 words; escape line
  breaks as \n. If nothing durable changed, return the prior notes unchanged (empty string if none).
```

- [ ] **Step 5: Add the review schema loader, rewrite `reviewNews` + `mapReviewResponse`, delete compact review code**

In `src/main/java/com/thesisguard/ai/OpenRouterAiClient.java`:

(a) Add a field next to `buyThesisResponseFormat`:

```java
    private final ResponseFormat reviewResponseFormat;
```

(b) In the constructor, after `this.dailyReviewUserTemplate = loadPrompt("prompts/daily_review_user_template.txt");`, add:

```java
        this.reviewResponseFormat = loadReviewResponseFormat();
```

(c) Add the loader next to `loadThesisResponseFormat`:

```java
    private ResponseFormat loadReviewResponseFormat() {
        try {
            JsonNode schema = objectMapper.readTree(loadPrompt("prompts/review_schema.json"));
            return new ResponseFormat("json_schema", new JsonSchemaSpec("daily_review", true, schema));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load daily-review JSON schema", ex);
        }
    }
```

(d) Replace the whole `reviewNews` method:

```java
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
                        reviewMaxTokens(newsItems.size()), reviewResponseFormat);
                return mapReviewResponse(retry);
            } catch (TruncatedJsonException retryEx) {
                log.debug("[OpenRouter] Review retry also truncated; using local conservative fallback");
                return localReviewFallback(stock, newsItems,
                        "OpenRouter output was truncated before valid JSON completed.");
            }
        }
    }
```

(e) Replace the whole `mapReviewResponse` method (and drop `private`):

```java
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
```

(f) Delete these now-unused members entirely: `mapCompactReviewResponse`, `buildCompactReviewUserPrompt`, `buildCompactNewsText`, and the constant `COMPACT_REVIEW_SYSTEM_PROMPT`. Keep `localReviewFallback`, `buildCompactThesisText` (still used by triage), `buildNewsItemsBlock`, and `NO_DATA` (still used elsewhere). Search for each deleted name to confirm zero remaining references before removing.

- [ ] **Step 6: Run the mapping test and confirm both methods pass**

Run: `.\mvnw.cmd test -Dtest=OpenRouterMappingTest`
Expected: PASS (both `mapsFlatThesisJsonToEveryField` and `mapsFlatReviewJsonToEveryField`).

- [ ] **Step 7: Run the full suite**

Run: `.\mvnw.cmd test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/prompts/review_schema.json \
        src/main/resources/prompts/daily_review_user_template.txt \
        src/main/java/com/thesisguard/ai/OpenRouterAiClient.java \
        src/test/java/com/thesisguard/ai/OpenRouterMappingTest.java
git commit -m "feat(ai): enforce a flat schema for the daily review and drop the compact fallback"
```

---

## Manual verification (after both tasks, against the live demo model)

Not a code step — run once with the real `openrouter.api-key` configured:

1. `docker compose up -d` then `.\mvnw.cmd spring-boot:run`.
2. Add a stock and generate a thesis; open the stock detail page in `thesisguard-app`.
3. Confirm **every panel is populated**: Final Rating, Conviction, Portfolio Role, Return Forecast, Business Essence, Growth Drivers, Moat, Financial Quality, Valuation View, Main Risks, Thesis Break Triggers, Daily Review Focus, and a prose Full Buy Thesis.
4. Check the logs show `finish_reason=stop` (not `length`) and no "compact fallback" / "truncated" lines.
5. Save a couple of news items and run a review; confirm the review renders and item analyses appear.
6. **Caveat:** strict structured outputs require the chosen review model's provider to support `json_schema`. If the review call errors with a provider/parameters message, switch `openrouter.review-model` to a structured-output-capable model (the thesis model already works this way).

---

## Self-Review

**Spec coverage:**
- "Flat thesis schema, 16 required fields, 1:1 to `ThesisAiResponse`" → Task 1 Steps 3, 6.
- "`conviction` enum direct; drop `convictionScore`/`convictionFromScore`" → Task 1 Step 6(b),(c).
- "Real `valuation_view`, prose `full_buy_thesis`" → Task 1 Steps 3, 5.
- "`thesis_break_triggers`/`daily_review_focus` arrays stored newline-joined" → schema arrays + `joinArray` in Step 6(b); asserted in Task 1 Step 1.
- "Keep `clampForecastToSize`" → Task 1 Step 6(a) calls it.
- "Delete compact thesis fallback; single higher-budget retry" → Task 1 Step 6(a),(c).
- "Review flat strict schema, 6 consumed groups; drop unused fields" → Task 2 Steps 3, 4.
- "Drop review compact/local fallback machinery (keep minimal net)" → Task 2 Step 5(d),(f): compact code deleted, `localReviewFallback` retained.
- "Files touched" list → matches the File Structure table.
- "Frontend/DB/entity/tests unchanged" → Global Constraints; no entity/DTO files modified.

**Placeholder scan:** No TBD/TODO; every code/prompt step contains full content.

**Type consistency:** `mapThesisResponse(String)` / `mapReviewResponse(String)` package-private, called identically in tests and in `generateBuyThesis`/`reviewNews`. `ReviewNewsAnalysisAiResponse(newsItemId, analysis, impactLevel)` constructed positionally with `(news_item_id, analysis, item_change_level)` — matches record order. `ReviewAiResponse(thesisChangeLevel, summary, thesisImpact, recommendedAction, newsAnalysis, updatedMemory)` and `ThesisAiResponse` 16-arg order both match the records. `OpenRouterProperties("test-key","m","rm","tm","nm")` matches the 5-arg record. `JsonSchemaSpec(name, strict, schema)` matches existing usage in `loadThesisResponseFormat`.
