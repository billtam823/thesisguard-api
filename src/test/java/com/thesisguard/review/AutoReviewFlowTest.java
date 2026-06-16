package com.thesisguard.review;

import com.thesisguard.alert.AlertRepository;
import com.thesisguard.news.NewsItem;
import com.thesisguard.news.NewsItemCreateRequest;
import com.thesisguard.news.NewsItemRepository;
import com.thesisguard.news.NewsItemResponse;
import com.thesisguard.news.NewsItemService;
import com.thesisguard.news.FetchedNewsItemResponse;
import com.thesisguard.ai.AiNewsSearchClient;
import com.thesisguard.seekingalpha.SeekingAlphaClient;
import com.thesisguard.openbb.OpenBbClient;
import com.thesisguard.openbb.OpenBbFilingItem;
import com.thesisguard.stock.Stock;
import com.thesisguard.stock.StockRepository;
import com.thesisguard.stock.StockStatus;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@SpringBootTest
class AutoReviewFlowTest {

    @Autowired private DailyNewsReviewService reviewService;
    @Autowired private NewsItemService newsItemService;
    @Autowired private StockRepository stockRepository;
    @Autowired private StockThesisRepository thesisRepository;
    @Autowired private NewsItemRepository newsItemRepository;
    @Autowired private DailyNewsReviewRepository reviewRepository;
    @Autowired private ThesisMonitorMemoryRepository memoryRepository;
    @Autowired private AlertRepository alertRepository;

    @MockitoBean private OpenBbClient openBbClient;
    @MockitoBean private SeekingAlphaClient seekingAlphaClient;
    @MockitoBean private AiNewsSearchClient aiNewsSearchClient;

    private Stock stock;

    @BeforeEach
    void setUp() {
        alertRepository.deleteAll();
        reviewRepository.deleteAll();
        memoryRepository.deleteAll();
        newsItemRepository.deleteAll();
        thesisRepository.deleteAll();
        stockRepository.deleteAll();

        stock = stockRepository.save(Stock.builder()
                .ticker("TEST")
                .exchange("NASDAQ")
                .companyName("Test Corp")
                .build());
        thesisRepository.save(StockThesis.builder()
                .stock(stock)
                .fullBuyThesis("full")
                .savedBuyThesisSummary("summary")
                .finalRating("Buy")
                .conviction("High")
                .portfolioRole("Core Growth")
                .coreThesis("core")
                .businessEssence("essence")
                .growthDrivers("drivers")
                .moatSummary("moat")
                .financialQuality("quality")
                .valuationView("valuation")
                .mainRisks("risks")
                .thesisBreakTriggers("triggers")
                .dailyReviewFocus("focus")
                .build());

        when(seekingAlphaClient.fetchCompanyNews(any())).thenReturn(List.of(
                new FetchedNewsItemResponse("TEST", "Routine product update", "https://news.example/1", LocalDate.now(), "Reuters", "Recent item"),
                new FetchedNewsItemResponse("TEST", "Old story outside ingest window", "https://news.example/2", LocalDate.now().minusDays(30), "Reuters", "Old item")
        ));
        when(openBbClient.fetchCompanyFilings(eq("TEST"), eq("8-K"), anyInt())).thenReturn(List.of(
                new OpenBbFilingItem(LocalDate.now().minusDays(1), "8-K", "https://sec.example/r", "https://sec.example/d", "doc", "2.02")
        ));
        when(openBbClient.fetchInsiderTrading(eq("TEST"), isNull(), isNull(), anyInt())).thenReturn(List.of());
    }

    @Test
    void ingestSavesNewItemsOnceAndSkipsItemsOutsideWindow() {
        assertThat(newsItemService.ingestLatest(stock)).isEqualTo(2); // recent headline + filing; old headline skipped
        assertThat(newsItemService.ingestLatest(stock)).isZero();
        assertThat(newsItemRepository.count()).isEqualTo(2);
        assertThat(newsItemRepository.findAll())
                .extracting(NewsItem::getDedupKey)
                .doesNotContainNull();
    }

    @Test
    void allNoiseDayProducesNoChangeAndSkipsExpensiveReview() {
        // The default fetch (routine headline + plain 8-K) contains no thesis-relevant keywords,
        // so triage marks everything noise: No_Change, no alert, no memory, but all items reviewed.
        newsItemService.ingestLatest(stock);
        DailyNewsReviewResponse first = reviewService.reviewPending("TEST");
        assertThat(first.thesisChangeLevel()).isEqualTo(ThesisChangeLevel.No_Change);
        assertThat(first.summary()).contains("none affect the long-term thesis");
        assertThat(newsItemRepository.findAll()).allSatisfy(item -> assertThat(item.getReviewedAt()).isNotNull());
        assertThat(memoryRepository.findByStockId(stock.getId())).isEmpty(); // noise must not touch memory
        assertThat(alertRepository.count()).isZero();

        DailyNewsReviewResponse second = reviewService.reviewPending("TEST");
        assertThat(second.thesisChangeLevel()).isEqualTo(ThesisChangeLevel.No_News_Found);
    }

    @Test
    void autoReviewReturnsNewItemsCountAndNullReviewWhileBackgroundJobRuns() {
        // autoReview now fires the review asynchronously; it returns immediately with review=null.
        AutoReviewResponse response = reviewService.autoReview("TEST");
        assertThat(response.newItemsCount()).isEqualTo(2);
        assertThat(response.review()).isNull();
    }

    @Test
    void materialNewsElevatesStatusRaisesAlertAndBuildsMemory() {
        when(seekingAlphaClient.fetchCompanyNews(any())).thenReturn(List.of(
                new FetchedNewsItemResponse("TEST", "Material impairment confirmed by auditor", "https://news.example/3", LocalDate.now(), "Reuters", "Bad news")
        ));
        when(openBbClient.fetchCompanyFilings(eq("TEST"), eq("8-K"), anyInt())).thenReturn(List.of());

        newsItemService.ingestLatest(stock);
        reviewService.reviewPending("TEST");

        assertThat(stockRepository.findById(stock.getId()).orElseThrow().getStatus()).isEqualTo(StockStatus.Reduce_Review);
        assertThat(alertRepository.count()).isEqualTo(1);
        ThesisMonitorMemory memory = memoryRepository.findByStockId(stock.getId()).orElseThrow();
        assertThat(memory.getMemoryText()).contains("Reviewed 1 item(s)");
    }

    @Test
    void mixedGroupReviewsOnlyMaterialItemsAndRecordsNoiseRows() {
        when(seekingAlphaClient.fetchCompanyNews(any())).thenReturn(List.of(
                new FetchedNewsItemResponse("TEST", "Material impairment confirmed by auditor", "https://news.example/3", LocalDate.now(), "Reuters", "Bad news"),
                new FetchedNewsItemResponse("TEST", "Routine product update", "https://news.example/4", LocalDate.now(), "Reuters", "Noise")
        ));
        when(openBbClient.fetchCompanyFilings(eq("TEST"), eq("8-K"), anyInt())).thenReturn(List.of());

        newsItemService.ingestLatest(stock);
        DailyNewsReviewResponse review = reviewService.reviewPending("TEST");

        assertThat(review.thesisChangeLevel()).isEqualTo(ThesisChangeLevel.Material_Change);
        // Both items appear on the review: the material one analyzed, the noise one tagged NOISE.
        assertThat(review.newsAnalysis()).hasSize(2);
        assertThat(review.newsAnalysis()).anySatisfy(item -> assertThat(item.impactLevel()).isEqualTo("NOISE"));
        assertThat(newsItemRepository.findAll()).allSatisfy(item -> assertThat(item.getReviewedAt()).isNotNull());
        // Classification is written back onto each item so the feed can show it inline.
        assertThat(newsItemRepository.findAll()).allSatisfy(item -> assertThat(item.getImpactLevel()).isNotBlank());
        assertThat(newsItemRepository.findAll()).anySatisfy(item -> assertThat(item.getImpactLevel()).isEqualTo("NOISE"));
    }

    @Test
    void unrelatedItemIsFlaggedNotRelatedAndNeverEscalated() {
        // "unrelated" in the title makes the mock triage return related=false; the word "material"
        // would normally mark it material, but an unrelated item must be forced to noise.
        when(seekingAlphaClient.fetchCompanyNews(any())).thenReturn(List.of(
                new FetchedNewsItemResponse("TEST", "Unrelated market-wide material selloff roundup", "https://news.example/5", LocalDate.now(), "Reuters", "Off-topic"),
                new FetchedNewsItemResponse("TEST", "Material impairment confirmed by auditor", "https://news.example/6", LocalDate.now(), "Reuters", "Real signal")
        ));
        when(openBbClient.fetchCompanyFilings(eq("TEST"), eq("8-K"), anyInt())).thenReturn(List.of());

        newsItemService.ingestLatest(stock);
        DailyNewsReviewResponse review = reviewService.reviewPending("TEST");

        // The related material item still drives the verdict and escalates.
        assertThat(review.thesisChangeLevel()).isEqualTo(ThesisChangeLevel.Material_Change);
        NewsItem unrelated = newsItemRepository.findAll().stream()
                .filter(n -> n.getTitle().startsWith("Unrelated")).findFirst().orElseThrow();
        assertThat(unrelated.getRelatedToStock()).isFalse();
        assertThat(unrelated.getImpactLevel()).isEqualTo("NOISE");
        assertThat(unrelated.getReviewedAt()).isNotNull();
        NewsItem material = newsItemRepository.findAll().stream()
                .filter(n -> n.getTitle().startsWith("Material")).findFirst().orElseThrow();
        assertThat(material.getRelatedToStock()).isTrue();
    }

    @Test
    void reviewPendingAppendsToMemoryAndKeepsPreviousVersion() {
        // Use material headlines so the expensive review (which writes memory) runs both times.
        when(seekingAlphaClient.fetchCompanyNews(any())).thenReturn(List.of(
                new FetchedNewsItemResponse("TEST", "Material impairment confirmed by auditor", "https://news.example/3", LocalDate.now(), "Reuters", "Bad news")
        ));
        when(openBbClient.fetchCompanyFilings(eq("TEST"), eq("8-K"), anyInt())).thenReturn(List.of());
        newsItemService.ingestLatest(stock);
        reviewService.reviewPending("TEST");
        String memoryAfterFirstRun = memoryRepository.findByStockId(stock.getId()).orElseThrow().getMemoryText();

        newsItemService.create("TEST", new NewsItemCreateRequest("Material restatement disclosed", null, null, null, null));
        reviewService.reviewPending("TEST");

        ThesisMonitorMemory memory = memoryRepository.findByStockId(stock.getId()).orElseThrow();
        assertThat(memory.getMemoryText()).contains("Reviewed 1 item(s)");
        assertThat(memory.getPreviousMemoryText()).isEqualTo(memoryAfterFirstRun);
    }

    @Test
    void ingestOnlyAddsToBacklogWithoutCreatingReview() {
        int inserted = newsItemService.ingest("TEST");
        assertThat(inserted).isEqualTo(2);
        assertThat(reviewRepository.count()).isZero();
        assertThat(newsItemRepository.findAll()).allSatisfy(item -> assertThat(item.getReviewedAt()).isNull());
    }

    @Test
    void duplicateItemsWithinAFetchAreDeduped() {
        when(seekingAlphaClient.fetchCompanyNews(any())).thenReturn(List.of(
                new FetchedNewsItemResponse("TEST", "Primary source story", "https://news.example/sa1", LocalDate.now(), "Seeking Alpha", "summary"),
                // Same url+title as the first item -> must dedup within the fetch.
                new FetchedNewsItemResponse("TEST", "Primary source story", "https://news.example/sa1", LocalDate.now(), "Seeking Alpha", "duplicate")
        ));

        int inserted = newsItemService.ingestLatest(stock);

        // The item (/sa1) once + the SEC filing from setUp; the duplicate /sa1 is skipped.
        assertThat(inserted).isEqualTo(2);
        assertThat(newsItemRepository.findAll()).filteredOn(n -> "https://news.example/sa1".equals(n.getUrl())).hasSize(1);
    }

    @Test
    void manualSaveIsIdempotentOnUrlAndTitle() {
        NewsItemCreateRequest request = new NewsItemCreateRequest("Same headline", "summary", "https://news.example/9", null, "Wire");
        NewsItemResponse firstSave = newsItemService.create("TEST", request);
        NewsItemResponse secondSave = newsItemService.create("TEST", request);
        assertThat(secondSave.id()).isEqualTo(firstSave.id());
        assertThat(newsItemRepository.count()).isEqualTo(1);
    }
}
