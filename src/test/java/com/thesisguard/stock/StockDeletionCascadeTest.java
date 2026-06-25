package com.thesisguard.stock;

import com.thesisguard.ai.AiNewsSearchClient;
import com.thesisguard.news.NewsItem;
import com.thesisguard.news.NewsItemRepository;
import com.thesisguard.openbb.OpenBbClient;
import com.thesisguard.review.DailyNewsReview;
import com.thesisguard.review.DailyNewsReviewRepository;
import com.thesisguard.review.NewsAnalysisItem;
import com.thesisguard.review.ThesisChangeLevel;
import com.thesisguard.seekingalpha.SeekingAlphaClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
class StockDeletionCascadeTest {

    @Autowired private StockService stockService;
    @Autowired private StockRepository stockRepository;
    @Autowired private NewsItemRepository newsItemRepository;
    @Autowired private DailyNewsReviewRepository reviewRepository;

    @MockitoBean private OpenBbClient openBbClient;
    @MockitoBean private SeekingAlphaClient seekingAlphaClient;
    @MockitoBean private AiNewsSearchClient aiNewsSearchClient;

    @Test
    void deletingStockCascadesThroughReviewsAndNewsAnalysisItems() {
        reviewRepository.deleteAll();
        newsItemRepository.deleteAll();
        stockRepository.deleteAll();

        Stock stock = stockRepository.save(Stock.builder()
                .ticker("ZZZ").companyName("Zeta Co").status(StockStatus.Hold).build());

        NewsItem news = newsItemRepository.save(NewsItem.builder()
                .stock(stock).title("Zeta ships a thing").publishedDate(LocalDate.now()).build());

        DailyNewsReview review = DailyNewsReview.builder()
                .stock(stock)
                .reviewDate(LocalDate.now())
                .thesisChangeLevel(ThesisChangeLevel.Minor_Change)
                .summary("mock summary")
                .build();
        review.addAnalysisItem(NewsAnalysisItem.builder()
                .newsItem(news).analysis("mock analysis").impactLevel("MINOR").build());
        reviewRepository.save(review);

        Long stockId = stock.getId();

        // Before the cascade fix this threw a ConstraintViolationException: deleting the stock
        // cascades to daily_news_reviews, still referenced by news_analysis_items.
        assertThatCode(() -> stockService.delete("ZZZ")).doesNotThrowAnyException();

        assertThat(stockRepository.findById(stockId)).isEmpty();
        assertThat(newsItemRepository.findByStockIdOrderByPublishedDateDescCreatedAtDesc(stockId)).isEmpty();
        assertThat(reviewRepository.findByStockIdOrderByReviewDateDescCreatedAtDesc(stockId)).isEmpty();
    }
}
