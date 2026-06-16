package com.thesisguard.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thesisguard.common.exception.ApiException;
import com.thesisguard.news.NewsItem;
import com.thesisguard.review.NewsTriageResult;
import com.thesisguard.review.ReviewAiResponse;
import com.thesisguard.stock.Stock;
import com.thesisguard.thesis.StockThesis;
import com.thesisguard.thesis.ThesisAiResponse;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

// Registered via AiClientConfig only when no other AiClient bean exists.
// @ConditionalOnMissingBean is unreliable on component-scanned classes (it can match its own definition).
public class MockAiClient implements AiClient {
    // Titles containing any of these are treated as potentially material by the mock triage,
    // mirroring the keyword mapping used by reviewNews so the two stay consistent in tests.
    private static final List<String> MATERIAL_KEYWORDS =
            List.of("broken", "material", "watch", "minor", "fraud", "recall", "lawsuit", "guidance cut");

    private final ObjectMapper objectMapper;

    public MockAiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public NewsTriageResult triageNews(Stock stock, StockThesis thesis, List<NewsItem> newsItems) {
        List<NewsTriageResult.Verdict> verdicts = new ArrayList<>();
        for (NewsItem item : newsItems) {
            String title = item.getTitle() == null ? "" : item.getTitle().toLowerCase();
            boolean related = !title.contains("unrelated");
            // An item not about this stock can never be material.
            boolean material = related && MATERIAL_KEYWORDS.stream().anyMatch(title::contains);
            String reason = !related
                    ? "Mock triage judged the item not about this stock."
                    : material ? "Mock triage matched a thesis-relevant keyword." : "Mock triage classified the item as noise.";
            verdicts.add(new NewsTriageResult.Verdict(item.getId(), material, related, reason));
        }
        return new NewsTriageResult(verdicts);
    }

    @Override
    public ThesisAiResponse generateBuyThesis(Stock stock) {
        String json = """
                {
                  "full_buy_thesis": "%s is a high-quality long-term compounder candidate. Mock full thesis (1,500–3,000 words in production). The business should be held while revenue growth, competitive position, and cash generation remain intact. Replace this with a real Gemini API key to generate a complete analysis.",
                  "saved_buy_thesis_summary": "AS OF: mock date (baselines refresh at each earnings report)\\nRATING: Buy | Conviction: High | Role: Core Growth\\n\\nTHESIS PILLARS\\nP1. Demand: %s has durable demand characteristics.\\nP2. Moat: brand, scale, and switching costs keep competitors at bay.\\nP3. Financials: margins expand with operating leverage and free cash flow stays healthy.\\nP4. Execution: management reinvests at high returns without supply disruption.\\nP5. Valuation: reasonable while P1-P3 hold; not independently checkable from news.\\n\\nBASELINE NUMBERS\\nMock baselines — replace AiClient with a real provider for actual figures.\\n\\nTRIPWIRES\\nTHESIS BROKEN: structural moat loss or governance failure (P2, P4).\\nMATERIAL CHANGE: sustained margin decline or demand contraction across two quarters (P1, P3).\\nWATCH CHANGE: a credible competitive or regulatory threat emerges (P2).\\nMINOR CHANGE: confirmed facts touching a pillar but within normal variance.\\n\\nNOISE - ALWAYS NO CHANGE\\nAnalyst price targets, daily stock price moves, unsourced rumors, opinion pieces.\\n\\nDAILY REVIEW QUESTIONS\\nFor each news item: which pillar does it touch, is it confirmed fact or speculation, does it cross a tripwire? Default to No Change.",
                  "final_rating": "Buy",
                  "conviction": "High",
                  "portfolio_role": "Core Growth",
                  "core_thesis": "• Durable demand characteristics\\n• Operating leverage supports margin expansion\\n• Reinvestment opportunities compound shareholder value",
                  "business_essence": "A scaled public company with durable products, recurring demand characteristics, and operating leverage potential.",
                  "growth_drivers": "Market expansion, product innovation, pricing power, and operating leverage.",
                  "moat_summary": "Brand: strong. Scale Economy: present. Switching Cost: moderate. Ecosystem: developing. Other dimensions require real AI analysis.",
                  "financial_quality": "Revenue growth: positive trend assumed. Margins: expanding. Free cash flow: healthy. Debt: manageable. Dilution: low. Profitability: established.",
                  "valuation_view": "Reasonable — mock view only. Use Gemini API key for real valuation analysis.",
                  "main_risks": "1. Execution missteps\\n2. Competition intensifying\\n3. Regulatory headwinds\\n4. Margin compression\\n5. Demand slowdown\\n6. Valuation multiple contraction",
                  "thesis_break_triggers": "Core moat weakening, revenue growth structurally slowing, margin collapse, management credibility issue, bubble-like valuation.",
                  "daily_review_focus": "Demand changes, competitive threats, regulation, management execution, financial quality signals, and thesis-break triggers."
                }
                """.formatted(stock.getCompanyName(), stock.getTicker());
        return read(json, ThesisAiResponse.class);
    }

    @Override
    public ReviewAiResponse reviewNews(Stock stock, StockThesis thesis, List<NewsItem> newsItems, String monitorMemory) {
        String joinedTitles = newsItems.stream().map(NewsItem::getTitle).reduce("", (left, right) -> left + " " + right).toLowerCase();
        String changeLevel = "No Change";
        if (joinedTitles.contains("broken")) {
            changeLevel = "Thesis Broken";
        } else if (joinedTitles.contains("material")) {
            changeLevel = "Material Change";
        } else if (joinedTitles.contains("watch")) {
            changeLevel = "Watch Change";
        } else if (joinedTitles.contains("minor")) {
            changeLevel = "Minor Change";
        }
        // Emit one analysis per item keyed by id so the service can match by newsItemId.
        String impactLevel = toImpactLevel(changeLevel);
        StringBuilder analyses = new StringBuilder();
        for (NewsItem item : newsItems) {
            if (analyses.length() > 0) {
                analyses.append(",");
            }
            analyses.append("{\"news_item_id\":\"%s\",\"analysis\":\"Mock AI analysis mapped this item to %s.\",\"impact_level\":\"%s\"}"
                    .formatted(item.getId(), changeLevel, impactLevel));
        }
        String json = """
                {
                  "thesis_change_level": "%s",
                  "summary": "Today's mock review classified the news impact for %s as %s.",
                  "thesis_impact": "Keyword-driven mock analysis for local testing. Titles containing watch, material, or broken exercise alert paths.",
                  "recommended_action": "Use this mock response for workflow testing only; replace AiClient with a real provider for production analysis.",
                  "news_analysis": [%s],
                  "updated_memory": %s
                }
                """.formatted(changeLevel, stock.getTicker(), changeLevel, analyses,
                jsonString(buildUpdatedMemory(monitorMemory, newsItems.size(), changeLevel)));
        return read(json, ReviewAiResponse.class);
    }

    private String buildUpdatedMemory(String monitorMemory, int newsCount, String changeLevel) {
        String header = monitorMemory == null || monitorMemory.isBlank()
                ? "== Mock monitoring journal =="
                : monitorMemory;
        return header + "\n[" + java.time.LocalDate.now() + "] Reviewed " + newsCount + " item(s); change level: " + changeLevel + ".";
    }

    private String jsonString(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Mock AI memory could not be serialized");
        }
    }

    private String toImpactLevel(String changeLevel) {
        return switch (changeLevel) {
            case "Thesis Broken" -> "Critical";
            case "Material Change" -> "High";
            case "Watch Change" -> "Medium";
            case "Minor Change" -> "Low";
            default -> "None";
        };
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Mock AI response could not be parsed");
        }
    }
}
