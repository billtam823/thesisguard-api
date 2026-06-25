package com.thesisguard.review;

import com.thesisguard.ai.AiClient;
import com.thesisguard.alert.AlertService;
import com.thesisguard.common.exception.ApiException;
import com.thesisguard.common.exception.ResourceNotFoundException;
import com.thesisguard.news.NewsItem;
import com.thesisguard.news.NewsItemService;
import com.thesisguard.stock.Stock;
import com.thesisguard.stock.StockRepository;
import com.thesisguard.stock.StockService;
import com.thesisguard.stock.StockStatus;
import com.thesisguard.thesis.StockThesis;
import com.thesisguard.thesis.StockThesisService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DailyNewsReviewService {
    private final StockService stockService;
    private final StockRepository stockRepository;
    private final StockThesisService thesisService;
    private final NewsItemService newsItemService;
    private final DailyNewsReviewRepository reviewRepository;
    private final ThesisMonitorMemoryRepository memoryRepository;
    private final AiClient aiClient;
    private final AlertService alertService;
    private final ReviewWorker reviewWorker;
    private final boolean triageEnabled;

    public DailyNewsReviewService(StockService stockService, StockRepository stockRepository,
                                  StockThesisService thesisService, NewsItemService newsItemService,
                                  DailyNewsReviewRepository reviewRepository, ThesisMonitorMemoryRepository memoryRepository,
                                  AiClient aiClient, AlertService alertService,
                                  @Lazy ReviewWorker reviewWorker,
                                  @Value("${thesisguard.triage.enabled:true}") boolean triageEnabled) {
        this.stockService = stockService;
        this.stockRepository = stockRepository;
        this.thesisService = thesisService;
        this.newsItemService = newsItemService;
        this.reviewRepository = reviewRepository;
        this.memoryRepository = memoryRepository;
        this.aiClient = aiClient;
        this.alertService = alertService;
        this.reviewWorker = reviewWorker;
        this.triageEnabled = triageEnabled;
    }

    @Transactional
    public DailyNewsReviewResponse reviewPending(String stockCode) {
        Stock stock = stockService.getEntity(stockCode);
        return DailyNewsReviewResponse.from(runReview(stock));
    }

    @Transactional
    public AutoReviewResponse autoReview(String stockCode) {
        Stock stock = stockService.getEntity(stockCode);
        thesisService.getEntity(stock.getId()); // fail early when no thesis exists
        if (!reviewWorker.startIfIdle(stock.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "Review is already running for " + stock.getTicker());
        }
        int newItems = newsItemService.ingestLatest(stock);
        stock.setReviewStatus("RUNNING");
        stockRepository.save(stock);
        Long stockId = stock.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                reviewWorker.runAsync(stockId);
            }
        });
        return new AutoReviewResponse(newItems, null);
    }

    /** Called by ReviewWorker after the transaction that set RUNNING has committed. */
    @Transactional
    public void executeReviewForWorker(Long stockId) {
        Stock stock = stockRepository.findById(stockId)
                .orElseThrow(() -> new IllegalStateException("Stock not found: " + stockId));
        runReview(stock);
        stock.setReviewStatus(null);
        stockRepository.save(stock);
    }

    private DailyNewsReview runReview(Stock stock) {
        StockThesis thesis = thesisService.getEntity(stock.getId());
        List<NewsItem> pendingNews = newsItemService.pendingEntities(stock.getId());

        if (pendingNews.isEmpty()) {
            return reviewRepository.save(buildNoNewsReview(stock));
        }

        // Cheap triage splits the day's group into breaking news vs noise so the expensive
        // doctrine review only runs on (and only reads) the items that could matter.
        NewsTriageResult triage = triageEnabled
                ? aiClient.triageNews(stock, thesis, pendingNews)
                : NewsTriageResult.allMaterial(pendingNews.stream().map(NewsItem::getId).toList(), "Triage disabled; reviewing all items.");
        Set<Long> materialIds = triage.materialIds();
        Map<Long, String> reasons = triage.reasonsById();
        Map<Long, Boolean> related = triage.relatedById();

        // Record relevance on every item; an item not about this stock can never be material,
        // so force it into the noise group regardless of the material verdict.
        for (NewsItem item : pendingNews) {
            item.setRelatedToStock(related.getOrDefault(item.getId(), true));
        }
        List<NewsItem> material = pendingNews.stream()
                .filter(n -> materialIds.contains(n.getId()) && Boolean.TRUE.equals(n.getRelatedToStock()))
                .toList();
        List<NewsItem> noise = pendingNews.stream().filter(n -> !material.contains(n)).toList();

        DailyNewsReview review;
        if (material.isEmpty()) {
            // All noise: no expensive call, no memory update, no alert.
            review = buildNoiseOnlyReview(stock, noise, reasons);
            markReviewed(pendingNews);
        } else {
            ThesisMonitorMemory memory = memoryRepository.findByStockId(stock.getId()).orElse(null);
            ReviewAiResponse ai = aiClient.reviewNews(stock, thesis, material, memory == null ? null : memory.getMemoryText());
            review = DailyNewsReview.builder()
                    .stock(stock)
                    .reviewDate(LocalDate.now())
                    .thesisChangeLevel(ai.thesisChangeLevel())
                    .summary(ai.summary())
                    .thesisImpact(ai.thesisImpact())
                    // The model may return no actions for a non-material change; never store blank.
                    .recommendedAction(ai.recommendedAction() == null || ai.recommendedAction().isBlank()
                            ? "Continue holding current status; no action required from today's news."
                            : ai.recommendedAction())
                    .build();
            addAnalysisItems(review, material, ai.newsAnalysis());
            addNoiseAnalysisItems(review, noise, reasons);
            applyStockStatus(stock, ai.thesisChangeLevel());
            markReviewed(pendingNews);
            updateMemory(stock, memory, ai.updatedMemory());
        }

        DailyNewsReview saved = reviewRepository.save(review);
        if (shouldAlert(saved.getThesisChangeLevel())) {
            alertService.createForReview(stock, saved);
        }
        return saved;
    }

    private DailyNewsReview buildNoNewsReview(Stock stock) {
        return DailyNewsReview.builder()
                .stock(stock)
                .reviewDate(LocalDate.now())
                .thesisChangeLevel(ThesisChangeLevel.No_News_Found)
                .summary("No unreviewed news found.")
                .thesisImpact("No thesis impact because every saved news item has already been reviewed.")
                .recommendedAction("Continue holding current status; no action required from today's news.")
                .build();
    }

    private DailyNewsReview buildNoiseOnlyReview(Stock stock, List<NewsItem> noise, Map<Long, String> reasons) {
        DailyNewsReview review = DailyNewsReview.builder()
                .stock(stock)
                .reviewDate(LocalDate.now())
                .thesisChangeLevel(ThesisChangeLevel.No_Change)
                .summary("Reviewed " + noise.size() + " news item(s); none affect the long-term thesis.")
                .thesisImpact("Triage classified every item as noise under the long-term doctrine, so the thesis is untouched.")
                .recommendedAction("Continue holding current status; no action required from today's news.")
                .build();
        addNoiseAnalysisItems(review, noise, reasons);
        return review;
    }

    private void addNoiseAnalysisItems(DailyNewsReview review, List<NewsItem> noise, Map<Long, String> reasons) {
        for (NewsItem item : noise) {
            String reason = reasons.get(item.getId());
            String analysis = reason == null || reason.isBlank() ? "Triaged as noise; no thesis impact." : reason;
            review.addAnalysisItem(NewsAnalysisItem.builder()
                    .newsItem(item)
                    .analysis(analysis)
                    .impactLevel("NOISE")
                    .build());
            applyClassification(item, "NOISE", analysis);
        }
    }

    // Write the per-item outcome back onto the news item so the feed can show it inline.
    private void applyClassification(NewsItem item, String impactLevel, String analysis) {
        item.setImpactLevel(impactLevel);
        item.setAnalysis(analysis);
    }

    private void markReviewed(List<NewsItem> newsItems) {
        LocalDateTime now = LocalDateTime.now();
        newsItems.forEach(item -> item.setReviewedAt(now));
    }

    private void updateMemory(Stock stock, ThesisMonitorMemory memory, String updatedMemory) {
        if (updatedMemory == null || updatedMemory.isBlank()) {
            return; // AI returned nothing durable; keep the existing memory untouched
        }
        if (memory == null) {
            memory = ThesisMonitorMemory.builder().stock(stock).memoryText(updatedMemory).build();
        } else {
            memory.setPreviousMemoryText(memory.getMemoryText());
            memory.setMemoryText(updatedMemory);
        }
        memoryRepository.save(memory);
    }

    @Transactional(readOnly = true)
    public ThesisMonitorMemoryResponse getMemory(String stockCode) {
        Stock stock = stockService.getEntity(stockCode);
        return memoryRepository.findByStockId(stock.getId())
                .map(ThesisMonitorMemoryResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("No monitoring memory exists for stock: " + stockCode));
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
                .orElseThrow(() -> new ResourceNotFoundException("No daily review exists for stock: " + stockCode));
    }

    private void addAnalysisItems(DailyNewsReview review, List<NewsItem> newsItems, List<ReviewNewsAnalysisAiResponse> analysis) {
        // Match the AI's per-item analyses to news items by newsItemId, not by list position:
        // the model often returns analyses for a subset (or out of order), so positional zipping
        // mislabels items and tags the overflow "Unknown". Items the review did not individually
        // flag default to NOISE (reviewed, no material impact) rather than a meaningless status.
        Map<Long, ReviewNewsAnalysisAiResponse> byId = new HashMap<>();
        if (analysis != null) {
            for (ReviewNewsAnalysisAiResponse item : analysis) {
                Long id = parseId(item.newsItemId());
                if (id != null) {
                    byId.put(id, item);
                }
            }
        }
        for (NewsItem newsItem : newsItems) {
            ReviewNewsAnalysisAiResponse aiItem = byId.get(newsItem.getId());
            String impactLevel = aiItem != null && aiItem.impactLevel() != null && !aiItem.impactLevel().isBlank()
                    ? aiItem.impactLevel()
                    : "NOISE";
            String analysisText = aiItem != null && aiItem.analysis() != null && !aiItem.analysis().isBlank()
                    ? aiItem.analysis()
                    : "Reviewed against the thesis but not individually flagged; no material impact noted.";
            review.addAnalysisItem(NewsAnalysisItem.builder()
                    .newsItem(newsItem)
                    .analysis(analysisText)
                    .impactLevel(impactLevel)
                    .build());
            applyClassification(newsItem, impactLevel, analysisText);
        }
    }

    private Long parseId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
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
