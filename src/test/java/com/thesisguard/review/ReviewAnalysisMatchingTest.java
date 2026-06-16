package com.thesisguard.review;

import com.thesisguard.ai.AiClient;
import com.thesisguard.news.NewsItem;
import com.thesisguard.news.NewsItemRepository;
import com.thesisguard.stock.Stock;
import com.thesisguard.stock.StockRepository;
import com.thesisguard.thesis.StockThesis;
import com.thesisguard.thesis.StockThesisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Regression test: the review must match per-item analyses to news items by newsItemId, not by
 * list position, and items the review did not individually flag must default to NOISE (never the
 * old "Unknown" fallback).
 */
@SpringBootTest
class ReviewAnalysisMatchingTest {

    @Autowired private DailyNewsReviewService reviewService;
    @Autowired private StockRepository stockRepository;
    @Autowired private StockThesisRepository thesisRepository;
    @Autowired private NewsItemRepository newsItemRepository;

    @MockitoBean private AiClient aiClient;

    private NewsItem first;
    private NewsItem second;

    @BeforeEach
    void setUp() {
        newsItemRepository.deleteAll();
        thesisRepository.deleteAll();
        stockRepository.deleteAll();

        Stock stock = stockRepository.save(Stock.builder().ticker("TEST").exchange("NASDAQ").companyName("Test Corp").build());
        thesisRepository.save(StockThesis.builder().stock(stock)
                .fullBuyThesis("full").savedBuyThesisSummary("summary").finalRating("Buy").conviction("High")
                .portfolioRole("Core Growth").coreThesis("core").businessEssence("essence").growthDrivers("drivers")
                .moatSummary("moat").financialQuality("quality").valuationView("valuation").mainRisks("risks")
                .thesisBreakTriggers("triggers").dailyReviewFocus("focus").build());

        first = newsItemRepository.save(NewsItem.builder().stock(stock).title("First headline").publishedDate(LocalDate.now()).build());
        second = newsItemRepository.save(NewsItem.builder().stock(stock).title("Second headline").publishedDate(LocalDate.now()).build());

        // Triage escalates both items (material + related).
        when(aiClient.triageNews(any(), any(), any())).thenReturn(new NewsTriageResult(List.of(
                new NewsTriageResult.Verdict(first.getId(), true, true, "material"),
                new NewsTriageResult.Verdict(second.getId(), true, true, "material"))));
    }

    @Test
    void unmatchedMaterialItemBecomesNoiseAndMatchedItemMatchesById() {
        // The review returns an analysis ONLY for the second item (out of order, subset) — the kind
        // of response that used to leave the first item tagged "Unknown" under positional zipping.
        when(aiClient.reviewNews(any(), any(), any(), any())).thenReturn(new ReviewAiResponse(
                ThesisChangeLevel.Material_Change,
                "summary", "impact", "action",
                List.of(new ReviewNewsAnalysisAiResponse(String.valueOf(second.getId()), "Concrete analysis for the second item.", "MAJOR")),
                null));

        reviewService.reviewPending("TEST");

        NewsItem reloadedFirst = newsItemRepository.findById(first.getId()).orElseThrow();
        NewsItem reloadedSecond = newsItemRepository.findById(second.getId()).orElseThrow();

        // The returned analysis lands on the second item (matched by id), not the first.
        assertThat(reloadedSecond.getImpactLevel()).isEqualTo("MAJOR");
        assertThat(reloadedSecond.getAnalysis()).isEqualTo("Concrete analysis for the second item.");

        // The unmatched first item defaults to NOISE — never "Unknown".
        assertThat(reloadedFirst.getImpactLevel()).isEqualTo("NOISE");
        assertThat(reloadedFirst.getImpactLevel()).isNotEqualTo("Unknown");
        assertThat(reloadedFirst.getAnalysis()).doesNotContain("No item-level AI analysis returned");
    }
}
