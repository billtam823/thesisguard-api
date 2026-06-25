package com.thesisguard.stock;

import com.thesisguard.common.exception.BadRequestException;
import com.thesisguard.common.exception.ResourceNotFoundException;
import com.thesisguard.openbb.OpenBbClient;
import com.thesisguard.openbb.OpenBbEquityProfile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
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
        OpenBbEquityProfile profile = openBbClient.fetchProfile(ticker);
        String exchange = resolveExchange(request.exchange(), profile);
        Stock stock = Stock.builder()
                .ticker(ticker)
                .exchange(exchange)
                .sector(profile != null ? profile.sector() : null)
                .industry(profile != null ? profile.industryCategory() : null)
                .companyName(request.companyName().trim())
                .status(StockStatus.Hold)
                .build();
        return StockResponse.from(stockRepository.save(stock));
    }

    private String resolveExchange(String requested, OpenBbEquityProfile profile) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim().toUpperCase(Locale.ROOT);
        }
        return profile != null ? profile.stockExchange() : null;
    }

    @Transactional(readOnly = true)
    public List<StockResponse> list() {
        return stockRepository.findAll().stream().map(StockResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<StockLookupResponse> lookup(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String trimmed = query.trim();
        List<StockLookupResponse> matches = openBbClient.searchEquities(trimmed).stream()
                .filter(item -> item.symbol() != null && !item.symbol().isBlank())
                .map(item -> new StockLookupResponse(item.symbol().toUpperCase(Locale.ROOT), item.name()))
                .toList();
        return rankByRelevance(matches, trimmed);
    }

    // Surface the closest matches first: exact ticker, then ticker prefix, then ticker substring,
    // then name-only matches; shorter and alphabetically-earlier tickers break ties. The SEC
    // provider returns hits in alphabetical order (e.g. ABBV before BB for "BB"), so without this
    // re-rank an exact ticker would not lead the dropdown. Cap to 20 AFTER ranking so an exact
    // match is never truncated away by a common substring.
    static List<StockLookupResponse> rankByRelevance(List<StockLookupResponse> matches, String query) {
        String upperQuery = query.trim().toUpperCase(Locale.ROOT);
        return matches.stream()
                .sorted(Comparator.comparingInt((StockLookupResponse m) -> relevanceRank(m.symbol(), upperQuery))
                        .thenComparingInt(m -> m.symbol().length())
                        .thenComparing(StockLookupResponse::symbol))
                .limit(20)
                .toList();
    }

    private static int relevanceRank(String symbol, String upperQuery) {
        String upperSymbol = symbol.toUpperCase(Locale.ROOT);
        if (upperSymbol.equals(upperQuery)) {
            return 0;
        }
        if (upperSymbol.startsWith(upperQuery)) {
            return 1;
        }
        if (upperSymbol.contains(upperQuery)) {
            return 2;
        }
        return 3; // matched on company name only
    }

    @Transactional(readOnly = true)
    public StockResponse get(String stockCode) {
        return StockResponse.from(getEntity(stockCode));
    }

    @Transactional
    public void delete(String stockCode) {
        stockRepository.delete(getEntity(stockCode));
    }

    @Transactional
    public StockResponse refreshProfile(String stockCode) {
        Stock stock = getEntity(stockCode);
        OpenBbEquityProfile profile = openBbClient.fetchProfile(stock.getProviderTicker());
        if (profile != null) {
            if (stock.getExchange() == null && profile.stockExchange() != null) {
                stock.setExchange(profile.stockExchange());
            }
            stock.setSector(profile.sector());
            stock.setIndustry(profile.industryCategory());
        }
        return StockResponse.from(stockRepository.save(stock));
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
