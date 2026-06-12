package com.thesisguard.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thesisguard.common.exception.ApiException;
import com.thesisguard.news.NewsItem;
import com.thesisguard.review.ReviewAiResponse;
import com.thesisguard.stock.Stock;
import com.thesisguard.thesis.StockThesis;
import com.thesisguard.thesis.ThesisAiResponse;
import org.springframework.http.HttpStatus;

import java.util.List;

// Registered via AiClientConfig only when no other AiClient bean exists.
// @ConditionalOnMissingBean is unreliable on component-scanned classes (it can match its own definition).
public class MockAiClient implements AiClient {
    private final ObjectMapper objectMapper;

    public MockAiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
    public ReviewAiResponse reviewNews(Stock stock, StockThesis thesis, List<NewsItem> newsItems) {
        String firstTitle = newsItems.isEmpty() ? "No title" : newsItems.getFirst().getTitle().replace("\"", "'");
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
        String json = """
                {
                  "thesis_change_level": "%s",
                  "summary": "Today's mock review classified the news impact for %s as %s.",
                  "thesis_impact": "Keyword-driven mock analysis for local testing. Titles containing watch, material, or broken exercise alert paths.",
                  "recommended_action": "Use this mock response for workflow testing only; replace AiClient with a real provider for production analysis.",
                  "news_analysis": [
                    {
                      "news_title": "%s",
                      "analysis": "Mock AI analysis mapped this news batch to %s.",
                      "impact_level": "%s"
                    }
                  ]
                }
                """.formatted(changeLevel, stock.getTicker(), changeLevel, firstTitle, changeLevel, toImpactLevel(changeLevel));
        return read(json, ReviewAiResponse.class);
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
