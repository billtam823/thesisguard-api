package com.thesisguard.thesis;

import com.thesisguard.ai.AiClient;
import com.thesisguard.common.exception.ApiException;
import com.thesisguard.common.exception.ResourceNotFoundException;
import com.thesisguard.stock.Stock;
import com.thesisguard.stock.StockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class StockThesisService {

    private static final Logger log = LoggerFactory.getLogger(StockThesisService.class);
    private final StockService stockService;
    private final StockThesisRepository thesisRepository;
    private final AiClient aiClient;
    private final ThesisGenerationWorker generationWorker;

    public StockThesisService(StockService stockService, StockThesisRepository thesisRepository,
                               AiClient aiClient, ThesisGenerationWorker generationWorker) {
        this.stockService = stockService;
        this.thesisRepository = thesisRepository;
        this.aiClient = aiClient;
        this.generationWorker = generationWorker;
    }

    @Transactional
    public StockThesisResponse generate(String stockCode) {
        Stock stock = stockService.getEntity(stockCode);
        if (!generationWorker.startIfIdle(stock.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "Thesis generation is already running for " + stock.getTicker());
        }
        log.debug("[Thesis] Generation started for stockCode={}", stockCode);
        StockThesis thesis = thesisRepository.findByStock(stock)
                .orElseGet(() -> StockThesis.builder().stock(stock)
                        .fullBuyThesis("").savedBuyThesisSummary("").finalRating("").conviction("")
                        .portfolioRole("").coreThesis("").businessEssence("").growthDrivers("")
                        .moatSummary("").financialQuality("").valuationView("").mainRisks("")
                        .thesisBreakTriggers("").dailyReviewFocus("").build());
        thesis.setGenerationStatus("RUNNING");
        thesis.setGenerationError(null);
        StockThesisResponse response = StockThesisResponse.from(thesisRepository.save(thesis));
        Long stockId = stock.getId();
        // Fire the async job only after this transaction commits so the worker can read the saved thesis.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                generationWorker.runAsync(stockId);
            }
        });
        return response;
    }

    @Transactional(readOnly = true)
    public StockThesisResponse get(String stockCode) {
        Stock stock = stockService.getEntity(stockCode);
        return StockThesisResponse.from(getEntity(stock.getId()));
    }

    @Transactional(readOnly = true)
    public StockThesis getEntity(Long stockId) {
        return thesisRepository.findByStockId(stockId)
                .orElseThrow(() -> new ResourceNotFoundException("No thesis exists for stock: " + stockId));
    }

    @Transactional
    public StockThesisResponse update(String stockCode, StockThesisUpdateRequest request) {
        Stock stock = stockService.getEntity(stockCode);
        StockThesis thesis = getEntity(stock.getId());
        apply(thesis, request);
        return StockThesisResponse.from(thesisRepository.save(thesis));
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
    }

    private void apply(StockThesis thesis, StockThesisUpdateRequest source) {
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
    }
}
