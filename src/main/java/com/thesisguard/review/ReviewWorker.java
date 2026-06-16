package com.thesisguard.review;

import com.thesisguard.stock.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ReviewWorker {

    private static final Logger log = LoggerFactory.getLogger(ReviewWorker.class);

    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final DailyNewsReviewService reviewService;
    private final StockRepository stockRepository;

    public ReviewWorker(DailyNewsReviewService reviewService, StockRepository stockRepository) {
        this.reviewService = reviewService;
        this.stockRepository = stockRepository;
    }

    /** Returns true if the slot was free (job started); false if already in-flight (caller should reject with 409). */
    public boolean startIfIdle(Long stockId) {
        return inFlight.add(stockId);
    }

    @Async
    public void runAsync(Long stockId) {
        try {
            reviewService.executeReviewForWorker(stockId);
            log.debug("[ReviewWorker] Completed for stockId={}", stockId);
        } catch (Exception ex) {
            log.error("[ReviewWorker] Failed for stockId={}: {}", stockId, ex.getMessage());
            stockRepository.findById(stockId).ifPresent(stock -> {
                stock.setReviewStatus("FAILED");
                stockRepository.save(stock);
            });
        } finally {
            inFlight.remove(stockId);
        }
    }
}
