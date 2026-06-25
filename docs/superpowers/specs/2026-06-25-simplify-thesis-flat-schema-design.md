# Design: Flatten the thesis & review AI output to match what is stored

**Date:** 2026-06-25
**Status:** Approved (pending spec review)
**Repo touched:** `thesisguard-api` (frontend `thesisguard-app` unaffected)

## Problem

On the internal demo, the buy thesis is generated with a cheaper OpenRouter model and
**several UI panels come back blank**. Generation is also slow and frequently falls back
to a degraded compact path.

Root cause is a **two-layer mismatch** in `OpenRouterAiClient`:

1. The model is asked to emit a large, deeply-nested **doctrine schema**
   (`buy_thesis_schema.json` — `trend.factorBreakdown` with 8 booleans, `fiveCriteria`
   with 5 scored sub-objects, `firstPrinciples`, `positionGuidance`, `growthForecast`, …).
2. That nested output is then **flattened** into the 16 flat fields the UI stores/shows,
   by string-joining sub-fields with labels (`mapThesisResponse` →
   `joinNonBlank("Industry: " + …, "Winning rules: " + …, …)`).

This produces blanks two ways, both worsened by a cheap model:

- **Missing nested field → blank panel.** `joinNonBlank` drops any piece whose value is
  empty, so if the model skips e.g. `positionGuidance`, the entire "Valuation View" panel
  renders blank.
- **Large output → truncation → fallback.** A cheap model on the ~12k-token budget often
  hits the length limit, throwing to `mapCompactThesisResponse` — a *different* mapping
  that fills a thinner set of fields and leaves others empty or semantically off.

There is also **semantic drift**: the "Valuation View" panel is fed position-sizing data
(`entryStrategy` / `maxPortfolioPercent`), and "Full Buy Thesis" shows the raw
pretty-printed JSON rather than prose, because the schema has no fields for either.

The **daily review** ("renew") has the same waste: `daily_review_user_template.txt` asks
for a large nested JSON (per-item `relevance`, `classification`, `evidenceQuality`,
`matchedKillCriteria`, plus top-level `buyOpportunity`, `recommendedStatus`,
`newWatchItems`, `alertRequired`, `alertMessage`, `coreAdvantageDirection`), but
`mapReviewResponse` reads only 6 of those groups — the rest is generated then discarded.
That wasted output drives truncation → the compact/local review fallback.

## Goals

- Every thesis UI panel is reliably filled — **no blank panels** on the cheap demo model.
- Faster and cheaper generation and review (smaller model output, less truncation).
- Fix the semantic drift: a real `valuation_view`, a prose `full_buy_thesis`.
- Remove the brittle flatten-and-fallback machinery.

## Non-goals (YAGNI)

- No change to the analytical doctrine itself — the 6-stage thesis pipeline and the
  K1–K4 review crisis categories remain, **as reasoning instructions in the system prompt**.
- No frontend changes, no DB/entity changes, no `StockThesis`/`StockThesisResponse` change.
- No change to `MockAiClient` (keeps tests green).
- No change to the deterministic size-cap (`clampForecastToSize`) or the triage gate.

## Core principle

Keep the doctrine in the **system prompt** as reasoning guidance, but have the model
**emit only the flat fields that are stored and displayed**. The Java record shapes
(`ThesisAiResponse`, `ReviewAiResponse`) are the contract and stay unchanged; the model's
output schema is reshaped to map 1:1 onto them, so the mapping methods become direct reads.

## Decisions

| Decision | Choice | Rationale |
| --- | --- | --- |
| Thesis output shape | **Flat strict json_schema**, ~16 required fields mapping 1:1 to `ThesisAiResponse` | Every field `required` under strict structured outputs ⇒ no blank panels; small output ⇒ little truncation. |
| `conviction` | Model emits enum **High/Medium/Low** directly | Drops the `convictionScore` → `convictionFromScore` indirection. |
| `valuation_view` | Real model field | Fixes the drift (was position-sizing). |
| `full_buy_thesis` | **Real prose narrative** from the model | Was raw pretty-printed JSON; prose is more useful for the demo. |
| `thesis_break_triggers` / `daily_review_focus` | Arrays of strings, stored newline-joined | Preserves how the review consumes them (`buildThesisJson` splits on newlines). |
| Size cap | **Keep `clampForecastToSize`** unchanged | Deterministic ceiling; only needs `return_multiple` + market cap. |
| Thesis fallback | **Delete** compact schema/prompt/mapping; on truncation, single retry at higher token budget on the same flat schema | Output is now small enough that truncation is rare; a same-shape retry is simpler than a second schema. |
| Review output shape | **Flat strict json_schema** for the 6 consumed groups | Mirrors the thesis; guarantees review fields; lets us drop the review compact/local fallbacks. |
| Review unused fields | **Removed** from the prompt | Never read by `mapReviewResponse`; pure token waste and truncation risk. |

## Thesis flat schema (new `buy_thesis_schema.json`)

All fields `required`, `additionalProperties:false`, strict structured outputs.

| JSON field | Type | → `ThesisAiResponse` |
| --- | --- | --- |
| `final_rating` | enum `GREEN`/`YELLOW`/`RED` | `finalRating` |
| `conviction` | enum `High`/`Medium`/`Low` | `conviction` |
| `portfolio_role` | string | `portfolioRole` |
| `saved_buy_thesis_summary` | string | `savedBuyThesisSummary` |
| `core_thesis` | string | `coreThesis` |
| `business_essence` | string | `businessEssence` |
| `growth_drivers` | string | `growthDrivers` |
| `moat_summary` | string | `moatSummary` |
| `financial_quality` | string | `financialQuality` |
| `valuation_view` | string | `valuationView` |
| `main_risks` | string | `mainRisks` |
| `thesis_break_triggers` | array[string] (3–7) | `thesisBreakTriggers` (newline-joined) |
| `daily_review_focus` | array[string] (3–6) | `dailyReviewFocus` (newline-joined) |
| `return_multiple` | enum `2x`/`3-5x`/`5-10x`/`10x+` | `returnMultiple` |
| `return_basis` | string | `returnBasis` |
| `full_buy_thesis` | string (prose) | `fullBuyThesis` |

`mapThesisResponse` becomes direct field reads (arrays joined with `\n`); the
`joinNonBlank`/label-stitching constructions are removed. `clampForecastToSize` runs after,
unchanged.

The `buy_thesis_system_prompt.txt` keeps the full 6-stage doctrine as reasoning guidance;
only its closing "OUTPUT FORMAT" section is rewritten to describe the flat fields. The
`buy_thesis_user_template.txt` field-guidance section is updated to the new field names.

## Review flat schema (new, enforced)

All fields `required`, strict structured outputs, mapping 1:1 onto `ReviewAiResponse`:

| JSON field | Type | → `ReviewAiResponse` |
| --- | --- | --- |
| `change_level` | enum `NONE`/`NOISE`/`MINOR`/`MAJOR`/`CRITICAL` | `thesisChangeLevel` (via `mapChangeLevel`) |
| `news_summary` | string | `summary` |
| `thesis_impact` | string | `thesisImpact` |
| `recommended_actions` | array[string] | `recommendedAction` (newline-joined) |
| `item_analyses` | array[{`news_item_id`, `item_change_level`, `analysis`}] | `newsAnalysis` |
| `updated_memory` | string (≤ ~2000 words) | `updatedMemory` |

The `daily_review_system_prompt.txt` keeps the K1–K4 doctrine; the user template drops the
removed fields and points at the new schema. `mapReviewResponse` reads the new field names.

## Code-level changes (`OpenRouterAiClient`)

- `generateBuyThesis`: drop the `TruncatedJsonException` → compact branch; replace with a
  single retry at a higher `max_tokens` on the same flat schema, then `clampForecastToSize`.
- `mapThesisResponse`: rewrite as direct reads of the flat fields.
- Delete `mapCompactThesisResponse`, `buildCompactThesisUserPrompt`.
- `reviewNews`: send the new review json_schema; drop the compact/local review fallback
  branches (keep a minimal safety net only if needed). Delete `mapCompactReviewResponse`,
  `buildCompactReviewUserPrompt`, `COMPACT_REVIEW_SYSTEM_PROMPT` if unused.
- `mapReviewResponse`: read the new field names; join `recommended_actions` with `\n`.
- Load the new review schema as a `ResponseFormat` (like `loadThesisResponseFormat`).

## Files touched

- `src/main/resources/prompts/buy_thesis_schema.json` (rewritten flat)
- `src/main/resources/prompts/buy_thesis_system_prompt.txt` (OUTPUT FORMAT section)
- `src/main/resources/prompts/buy_thesis_user_template.txt` (field guidance)
- `src/main/resources/prompts/daily_review_user_template.txt` (trim + new schema)
- `src/main/resources/prompts/review_schema.json` (new)
- `src/main/java/com/thesisguard/ai/OpenRouterAiClient.java` (mappings, fallbacks)

## Risk / compatibility

- **Frontend:** unchanged — `StockThesisResponse` JSON keys are identical.
- **DB / entity:** unchanged — same `StockThesis` columns; arrays stored newline-joined.
- **Tests:** use `MockAiClient` and don't touch the private mapping methods → stay green.
- **Behavioral change:** only the AI output shape (the intended fix). The doctrine and the
  size-cap are preserved, so verdicts and forecasts stay calibrated.

## Success criteria

- Generating a thesis on the demo's cheap model fills **all** UI panels (no blanks) on a
  representative set of tickers.
- No `mapCompactThesisResponse` / compact-review fallback in normal operation (verified via
  logs: `finish_reason=stop`, not `length`).
- `final_rating`, `conviction`, `return_multiple`, and the size-cap behave as before.
- `.\mvnw.cmd test` passes.
