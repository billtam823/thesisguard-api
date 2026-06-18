package com.thesisguard.config;

import com.thesisguard.stock.Stock;
import com.thesisguard.stock.StockRepository;
import com.thesisguard.thesis.StockThesis;
import com.thesisguard.thesis.StockThesisRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Async thesis generation and reviews mark their DB row RUNNING, then DONE/FAILED when the worker
 * finishes. If the JVM restarts (or crashes) mid-job the worker never finishes, leaving the row
 * stuck RUNNING forever — which also disables the regenerate/review buttons in the UI. A fresh JVM
 * has no jobs in flight, so on startup any RUNNING row is definitionally orphaned: reset it to
 * FAILED so the user can retry.
 */
@Component
public class JobStateReconciler {

    private static final Logger log = LoggerFactory.getLogger(JobStateReconciler.class);

    private final StockThesisRepository thesisRepository;
    private final StockRepository stockRepository;

    public JobStateReconciler(StockThesisRepository thesisRepository, StockRepository stockRepository) {
        this.thesisRepository = thesisRepository;
        this.stockRepository = stockRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void resetOrphanedJobs() {
        List<StockThesis> stuckTheses = thesisRepository.findByGenerationStatus("RUNNING");
        for (StockThesis thesis : stuckTheses) {
            thesis.setGenerationStatus("FAILED");
            thesis.setGenerationError("Generation was interrupted by a server restart. Please regenerate.");
        }
        if (!stuckTheses.isEmpty()) {
            thesisRepository.saveAll(stuckTheses);
        }

        List<Stock> stuckReviews = stockRepository.findByReviewStatus("RUNNING");
        for (Stock stock : stuckReviews) {
            stock.setReviewStatus("FAILED");
        }
        if (!stuckReviews.isEmpty()) {
            stockRepository.saveAll(stuckReviews);
        }

        if (!stuckTheses.isEmpty() || !stuckReviews.isEmpty()) {
            log.info("[JobStateReconciler] Reset {} stuck thesis generation(s) and {} stuck review(s) to FAILED on startup",
                    stuckTheses.size(), stuckReviews.size());
        }
    }
}
