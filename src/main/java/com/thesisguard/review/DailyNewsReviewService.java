package com.thesisguard.review;

import com.thesisguard.ai.AiClient;
import com.thesisguard.alert.AlertService;
import com.thesisguard.news.NewsItem;
import com.thesisguard.news.NewsItemService;
import com.thesisguard.stock.Stock;
import com.thesisguard.stock.StockService;
import com.thesisguard.stock.StockStatus;
import com.thesisguard.thesis.StockThesis;
import com.thesisguard.thesis.StockThesisService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class DailyNewsReviewService {
    private final StockService stockService;
    private final StockThesisService thesisService;
    private final NewsItemService newsItemService;
    private final DailyNewsReviewRepository reviewRepository;
    private final AiClient aiClient;
    private final AlertService alertService;

    public DailyNewsReviewService(StockService stockService, StockThesisService thesisService, NewsItemService newsItemService,
                                  DailyNewsReviewRepository reviewRepository, AiClient aiClient, AlertService alertService) {
        this.stockService = stockService;
        this.thesisService = thesisService;
        this.newsItemService = newsItemService;
        this.reviewRepository = reviewRepository;
        this.aiClient = aiClient;
        this.alertService = alertService;
    }

    @Transactional
    public DailyNewsReviewResponse reviewToday(String stockCode) {
        Stock stock = stockService.getEntity(stockCode);
        StockThesis thesis = thesisService.getEntity(stock.getId());
        List<NewsItem> todayNews = newsItemService.todayEntities(stock.getId());

        DailyNewsReview review;
        if (todayNews.isEmpty()) {
            review = DailyNewsReview.builder()
                    .stock(stock)
                    .reviewDate(LocalDate.now())
                    .thesisChangeLevel(ThesisChangeLevel.No_News_Found)
                    .summary("No news found for today's review.")
                    .thesisImpact("No thesis impact because no news items were available.")
                    .recommendedAction("Continue holding current status and add news before running a substantive review.")
                    .build();
        } else {
            ReviewAiResponse ai = aiClient.reviewNews(stock, thesis, todayNews);
            review = DailyNewsReview.builder()
                    .stock(stock)
                    .reviewDate(LocalDate.now())
                    .thesisChangeLevel(ai.thesisChangeLevel())
                    .summary(ai.summary())
                    .thesisImpact(ai.thesisImpact())
                    .recommendedAction(ai.recommendedAction())
                    .build();
            addAnalysisItems(review, todayNews, ai.newsAnalysis());
            applyStockStatus(stock, ai.thesisChangeLevel());
        }

        DailyNewsReview saved = reviewRepository.save(review);
        if (shouldAlert(saved.getThesisChangeLevel())) {
            alertService.createForReview(stock, saved);
        }
        return DailyNewsReviewResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<DailyNewsReviewResponse> list(String stockCode) {
        Stock stock = stockService.getEntity(stockCode);
        return reviewRepository.findByStockIdOrderByReviewDateDescCreatedAtDesc(stock.getId()).stream().map(DailyNewsReviewResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public DailyNewsReviewResponse latest(String stockCode) {
        Stock stock = stockService.getEntity(stockCode);
        return reviewRepository.findFirstByStockIdOrderByReviewDateDescCreatedAtDesc(stock.getId())
                .map(DailyNewsReviewResponse::from)
                .orElseThrow(() -> new com.thesisguard.common.exception.ResourceNotFoundException("No daily review exists for stock: " + stockCode));
    }

    private void addAnalysisItems(DailyNewsReview review, List<NewsItem> newsItems, List<ReviewNewsAnalysisAiResponse> analysis) {
        for (int i = 0; i < newsItems.size(); i++) {
            ReviewNewsAnalysisAiResponse aiItem = analysis != null && i < analysis.size()
                    ? analysis.get(i)
                    : new ReviewNewsAnalysisAiResponse(newsItems.get(i).getTitle(), "No item-level AI analysis returned.", "Unknown");
            review.addAnalysisItem(NewsAnalysisItem.builder()
                    .newsItem(newsItems.get(i))
                    .analysis(aiItem.analysis())
                    .impactLevel(aiItem.impactLevel())
                    .build());
        }
    }

    private void applyStockStatus(Stock stock, ThesisChangeLevel level) {
        switch (level) {
            case Watch_Change -> stock.setStatus(StockStatus.Watch);
            case Material_Change -> stock.setStatus(StockStatus.Reduce_Review);
            case Thesis_Broken -> stock.setStatus(StockStatus.Sell_Review);
            case No_Change, Minor_Change, No_News_Found -> {
                // Keep current stock status.
            }
        }
    }

    private boolean shouldAlert(ThesisChangeLevel level) {
        return level == ThesisChangeLevel.Watch_Change
                || level == ThesisChangeLevel.Material_Change
                || level == ThesisChangeLevel.Thesis_Broken;
    }
}
