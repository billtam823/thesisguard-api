package com.thesisguard.news;

import com.thesisguard.openbb.OpenBbClient;
import com.thesisguard.openbb.OpenBbNewsItem;
import com.thesisguard.stock.Stock;
import com.thesisguard.stock.StockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class NewsItemService {
    private final StockService stockService;
    private final NewsItemRepository newsItemRepository;
    private final OpenBbClient openBbClient;

    public NewsItemService(StockService stockService, NewsItemRepository newsItemRepository, OpenBbClient openBbClient) {
        this.stockService = stockService;
        this.newsItemRepository = newsItemRepository;
        this.openBbClient = openBbClient;
    }

    @Transactional
    public NewsItemResponse create(String stockCode, NewsItemCreateRequest request) {
        Stock stock = stockService.getEntity(stockCode);
        NewsItem item = NewsItem.builder()
                .stock(stock)
                .title(request.title().trim())
                .summary(request.summary())
                .url(request.url())
                .publishedDate(request.publishedDate() == null ? LocalDate.now() : request.publishedDate())
                .build();
        return NewsItemResponse.from(newsItemRepository.save(item));
    }

    @Transactional(readOnly = true)
    public List<NewsItemResponse> list(String stockCode) {
        Stock stock = stockService.getEntity(stockCode);
        return newsItemRepository.findByStockIdOrderByPublishedDateDescCreatedAtDesc(stock.getId()).stream().map(NewsItemResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<NewsItemResponse> today(String stockCode) {
        Stock stock = stockService.getEntity(stockCode);
        return newsItemRepository.findByStockIdAndPublishedDateOrderByCreatedAtDesc(stock.getId(), LocalDate.now()).stream().map(NewsItemResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<NewsItem> todayEntities(Long stockId) {
        return newsItemRepository.findByStockIdAndPublishedDateOrderByCreatedAtDesc(stockId, LocalDate.now());
    }

    public List<FetchedNewsItemResponse> previewNews(String stockCode, LocalDate date) {
        return openBbClient.fetchCompanyNews(stockCode.toUpperCase(), date).stream()
                .filter(item -> item.title() != null)
                .filter(item -> {
                    if (date == null) return true;
                    return item.date() != null && item.date().toLocalDate().equals(date);
                })
                .map(FetchedNewsItemResponse::from)
                .toList();
    }

    private static final int FILINGS_FETCH_LIMIT = 40;
    private static final int INSIDER_FETCH_LIMIT = 200;

    public List<FetchedNewsItemResponse> previewFilings(String stockCode, LocalDate date) {
        Stock stock = stockService.getEntity(stockCode);
        if (!stock.isUsListed()) {
            return List.of();
        }
        String symbol = stock.getTicker().toUpperCase();
        return openBbClient.fetchCompanyFilings(symbol, "8-K", FILINGS_FETCH_LIMIT).stream()
                .filter(filing -> date == null || date.equals(filing.filingDate()))
                .map(filing -> FetchedNewsItemResponse.fromFiling(symbol, filing))
                .toList();
    }

    public List<FetchedNewsItemResponse> previewInsiderTrades(String stockCode, LocalDate date) {
        Stock stock = stockService.getEntity(stockCode);
        if (!stock.isUsListed()) {
            return List.of();
        }
        String symbol = stock.getTicker().toUpperCase();
        return openBbClient.fetchInsiderTrading(symbol, date, date, INSIDER_FETCH_LIMIT).stream()
                .filter(tx -> tx.ownerName() != null)
                .map(FetchedNewsItemResponse::fromInsider)
                .toList();
    }

}
