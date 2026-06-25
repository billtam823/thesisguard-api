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
