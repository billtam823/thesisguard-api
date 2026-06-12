package com.thesisguard.thesis;

import com.thesisguard.ai.AiClient;
import com.thesisguard.common.exception.ResourceNotFoundException;
import com.thesisguard.stock.Stock;
import com.thesisguard.stock.StockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockThesisService {

    private static final Logger log = LoggerFactory.getLogger(StockThesisService.class);
    private final StockService stockService;
    private final StockThesisRepository thesisRepository;
    private final AiClient aiClient;

    public StockThesisService(StockService stockService, StockThesisRepository thesisRepository, AiClient aiClient) {
        this.stockService = stockService;
        this.thesisRepository = thesisRepository;
        this.aiClient = aiClient;
    }

    @Transactional
    public StockThesisResponse generate(String stockCode) {
        log.debug("[Thesis] Generate requested for stockCode={}", stockCode);
        Stock stock = stockService.getEntity(stockCode);
        log.debug("[Thesis] Resolved stock: id={}, ticker={}, providerTicker={}", stock.getId(), stock.getTicker(), stock.getProviderTicker());
        log.debug("[Thesis] Calling AI client to generate buy thesis...");
        ThesisAiResponse ai = aiClient.generateBuyThesis(stock);
        log.debug("[Thesis] AI response received: rating={}, conviction={}", ai.finalRating(), ai.conviction());
        StockThesis thesis = thesisRepository.findByStock(stock).orElseGet(() -> StockThesis.builder().stock(stock).build());
        apply(thesis, ai);
        StockThesisResponse saved = StockThesisResponse.from(thesisRepository.save(thesis));
        log.debug("[Thesis] Thesis saved with id={}", saved.id());
        return saved;
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
