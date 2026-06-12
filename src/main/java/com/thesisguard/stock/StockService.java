package com.thesisguard.stock;

import com.thesisguard.common.exception.BadRequestException;
import com.thesisguard.common.exception.ResourceNotFoundException;
import com.thesisguard.openbb.OpenBbClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class StockService {
    private final StockRepository stockRepository;
    private final OpenBbClient openBbClient;

    public StockService(StockRepository stockRepository, OpenBbClient openBbClient) {
        this.stockRepository = stockRepository;
        this.openBbClient = openBbClient;
    }

    @Transactional
    public StockResponse create(StockCreateRequest request) {
        String ticker = normalizeTicker(request.ticker());
        if (stockRepository.existsByTicker(ticker)) {
            throw new BadRequestException("Stock already exists in watchlist: " + ticker);
        }
        String exchange = resolveExchange(request.exchange(), ticker);
        Stock stock = Stock.builder()
                .ticker(ticker)
                .exchange(exchange)
                .companyName(request.companyName().trim())
                .status(StockStatus.Hold)
                .build();
        return StockResponse.from(stockRepository.save(stock));
    }

    private String resolveExchange(String requested, String ticker) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim().toUpperCase(Locale.ROOT);
        }
        return openBbClient.fetchExchange(ticker);
    }

    @Transactional(readOnly = true)
    public List<StockResponse> list() {
        return stockRepository.findAll().stream().map(StockResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public StockResponse get(String stockCode) {
        return StockResponse.from(getEntity(stockCode));
    }

    @Transactional
    public void delete(String stockCode) {
        stockRepository.delete(getEntity(stockCode));
    }

    @Transactional(readOnly = true)
    public Stock getEntity(Long stockId) {
        return stockRepository.findById(stockId)
                .orElseThrow(() -> new ResourceNotFoundException("Stock not found: " + stockId));
    }

    @Transactional(readOnly = true)
    public Stock getEntity(String stockCode) {
        try {
            return getEntity(Long.parseLong(stockCode));
        } catch (NumberFormatException e) {
            return stockRepository.findByTicker(stockCode.toUpperCase(Locale.ROOT))
                    .orElseThrow(() -> new ResourceNotFoundException("Stock not found: " + stockCode));
        }
    }

    private String normalizeTicker(String ticker) {
        return ticker.trim().toUpperCase(Locale.ROOT);
    }
}
