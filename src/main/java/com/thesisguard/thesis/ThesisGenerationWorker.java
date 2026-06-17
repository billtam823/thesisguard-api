package com.thesisguard.thesis;

import com.thesisguard.ai.AiClient;
import com.thesisguard.stock.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ThesisGenerationWorker {

    private static final Logger log = LoggerFactory.getLogger(ThesisGenerationWorker.class);

    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final StockRepository stockRepository;
    private final StockThesisRepository thesisRepository;
    private final AiClient aiClient;

    public ThesisGenerationWorker(StockRepository stockRepository, StockThesisRepository thesisRepository, AiClient aiClient) {
        this.stockRepository = stockRepository;
        this.thesisRepository = thesisRepository;
        this.aiClient = aiClient;
    }

    /** Returns true if the slot was free (job started); false if already in-flight (caller should reject with 409). */
    public boolean startIfIdle(Long stockId) {
        return inFlight.add(stockId);
    }

    @Async
    public void runAsync(Long stockId) {
        try {
            var stock = stockRepository.findById(stockId)
                    .orElseThrow(() -> new IllegalStateException("Stock not found: " + stockId));
            ThesisAiResponse ai = aiClient.generateBuyThesis(stock);
            StockThesis thesis = thesisRepository.findByStockId(stockId)
                    .orElseThrow(() -> new IllegalStateException("Thesis record not found for stock: " + stockId));
            apply(thesis, ai);
            thesis.setGenerationStatus("DONE");
            thesis.setGenerationError(null);
            thesisRepository.save(thesis);
            log.debug("[ThesisGen] Completed for stockId={}", stockId);
        } catch (Exception ex) {
            log.error("[ThesisGen] Failed for stockId={}: {}", stockId, ex.getMessage());
            thesisRepository.findByStockId(stockId).ifPresent(thesis -> {
                thesis.setGenerationStatus("FAILED");
                String msg = ex.getMessage();
                thesis.setGenerationError(msg != null && msg.length() > 500 ? msg.substring(0, 500) : msg);
                thesisRepository.save(thesis);
            });
        } finally {
            inFlight.remove(stockId);
        }
    }

    private void apply(StockThesis thesis, ThesisAiResponse source) {
        thesis.setFullBuyThesis(source.fullBuyThesis());
        thesis.setSavedBuyThesisSummary(source.savedBuyThesisSummary());
        thesis.setFinalRating(source.finalRating());
        thesis.setConviction(source.conviction());
        thesis.setPortfolioRole(source.portfolioRole());
        thesis.setCoreThesis(source.coreThesis());
        thesis.setBusinessEssence(source.businessEssence());
        thesis.setGrowthDrivers(source.growthDrivers());
        thesis.setMoatSummary(source.moatSummary());
        thesis.setFinancialQuality(source.financialQuality());
        thesis.setValuationView(source.valuationView());
        thesis.setMainRisks(source.mainRisks());
        thesis.setThesisBreakTriggers(source.thesisBreakTriggers());
        thesis.setDailyReviewFocus(source.dailyReviewFocus());
        thesis.setReturnMultiple(source.returnMultiple());
        thesis.setReturnBasis(source.returnBasis());
    }
}
