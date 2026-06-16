package com.thesisguard.news;

import com.thesisguard.ai.AiNewsSearchClient;
import com.thesisguard.openbb.OpenBbClient;
import com.thesisguard.seekingalpha.SeekingAlphaClient;
import com.thesisguard.stock.Stock;
import com.thesisguard.stock.StockService;
import com.thesisguard.thesis.StockThesis;
import com.thesisguard.thesis.StockThesisRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class NewsItemService {
    private static final Logger log = LoggerFactory.getLogger(NewsItemService.class);

    private final StockService stockService;
    private final NewsItemRepository newsItemRepository;
    private final OpenBbClient openBbClient;
    private final SeekingAlphaClient seekingAlphaClient;
    private final AiNewsSearchClient aiNewsSearchClient;
    private final StockThesisRepository thesisRepository;
    private final int ingestWindowDays;

    public NewsItemService(StockService stockService, NewsItemRepository newsItemRepository, OpenBbClient openBbClient,
                           SeekingAlphaClient seekingAlphaClient, AiNewsSearchClient aiNewsSearchClient, StockThesisRepository thesisRepository,
                           @Value("${thesisguard.ingest-window-days:7}") int ingestWindowDays) {
        this.stockService = stockService;
        this.newsItemRepository = newsItemRepository;
        this.openBbClient = openBbClient;
        this.seekingAlphaClient = seekingAlphaClient;
        this.aiNewsSearchClient = aiNewsSearchClient;
        this.thesisRepository = thesisRepository;
        this.ingestWindowDays = ingestWindowDays;
    }

    @Transactional
    public NewsItemResponse create(String stockCode, NewsItemCreateRequest request) {
        Stock stock = stockService.getEntity(stockCode);
        String title = request.title().trim();
        String dedupKey = NewsItem.computeDedupKey(request.url(), title);
        return newsItemRepository.findByStockIdAndDedupKey(stock.getId(), dedupKey)
                .map(NewsItemResponse::from)
                .orElseGet(() -> NewsItemResponse.from(newsItemRepository.save(NewsItem.builder()
                        .stock(stock)
                        .title(title)
                        .summary(request.summary())
                        .url(request.url())
                        .publishedDate(request.publishedDate() == null ? LocalDate.now() : request.publishedDate())
                        .source(request.source())
                        .build())));
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

    @Transactional(readOnly = true)
    public List<NewsItem> pendingEntities(Long stockId) {
        return newsItemRepository.findByStockIdAndReviewedAtIsNullOrderByPublishedDateAscCreatedAtAsc(stockId);
    }

    /**
     * Ingest-only entry point: fetches and stores the latest news for a stock without running
     * a review, so news accumulates in the backlog between manual/scheduled reviews. Returns
     * the number of newly saved items.
     */
    @Transactional
    public int ingest(String stockCode) {
        return ingestLatest(stockService.getEntity(stockCode));
    }

    /**
     * Fetches recent headlines, 8-K filings, and insider trades for the stock and
     * saves every item not already on file (dedup by url+title). Items older than
     * the ingest window are skipped so the first run does not flood the review
     * backlog with historical filings. A failing source is logged and skipped so
     * the remaining sources still ingest. Returns the number of newly saved items.
     */
    @Transactional
    public int ingestLatest(Stock stock) {
        List<FetchedNewsItemResponse> fetched = new ArrayList<>();
        // Company news comes from Seeking Alpha (RapidAPI). SEC 8-K filings and Form 4 insider
        // trades still come from OpenBB for US-listed stocks.
        addSource(fetched, "seeking-alpha", () -> seekingAlphaClient.fetchCompanyNews(stock));
        if (stock.isUsListed()) {
            String symbol = stock.getTicker().toUpperCase();
            addSource(fetched, "SEC filings", () -> openBbClient.fetchCompanyFilings(symbol, "8-K", FILINGS_FETCH_LIMIT).stream()
                    .map(filing -> FetchedNewsItemResponse.fromFiling(symbol, filing))
                    .toList());
            addSource(fetched, "insider trades", () -> openBbClient.fetchInsiderTrading(symbol, null, null, INSIDER_FETCH_LIMIT).stream()
                    .filter(tx -> tx.ownerName() != null)
                    .map(FetchedNewsItemResponse::fromInsider)
                    .toList());
        }
        // AI web-search collector — optional supplement, disabled by default (no-ops unless enabled).
        StockThesis thesis = thesisRepository.findByStockId(stock.getId()).orElse(null);
        addSource(fetched, "ai-search", () -> aiNewsSearchClient.searchRecentNews(stock, thesis));

        LocalDate windowStart = LocalDate.now().minusDays(ingestWindowDays);
        Set<String> seenKeys = new HashSet<>();
        int inserted = 0;
        for (FetchedNewsItemResponse item : fetched) {
            if (item.title() == null || item.title().isBlank()) continue;
            if (item.publishedDate() != null && item.publishedDate().isBefore(windowStart)) continue;
            String title = item.title().trim();
            if (title.length() > 500) title = title.substring(0, 500);
            String url = item.url() != null && item.url().length() <= 1000 ? item.url() : null;
            String dedupKey = NewsItem.computeDedupKey(url, title);
            if (!seenKeys.add(dedupKey) || newsItemRepository.findByStockIdAndDedupKey(stock.getId(), dedupKey).isPresent()) {
                continue;
            }
            newsItemRepository.save(NewsItem.builder()
                    .stock(stock)
                    .title(title)
                    .summary(item.summary())
                    .content(item.content())
                    .url(url)
                    .publishedDate(item.publishedDate() == null ? LocalDate.now() : item.publishedDate())
                    .source(truncateSource(item.source()))
                    .dedupKey(dedupKey)
                    .build());
            inserted++;
        }
        log.debug("[Ingest] {} fetched, {} newly saved for stock {}", fetched.size(), inserted, stock.getTicker());
        return inserted;
    }

    private void addSource(List<FetchedNewsItemResponse> target, String label, java.util.function.Supplier<List<FetchedNewsItemResponse>> fetcher) {
        try {
            target.addAll(fetcher.get());
        } catch (Exception ex) {
            log.warn("[Ingest] Skipping {} source: {}", label, ex.getMessage());
        }
    }

    private String truncateSource(String source) {
        if (source == null) return null;
        return source.length() <= 64 ? source : source.substring(0, 64);
    }

    public List<FetchedNewsItemResponse> previewNews(String stockCode, LocalDate date) {
        Stock stock = stockService.getEntity(stockCode);
        return seekingAlphaClient.fetchCompanyNews(stock).stream()
                .filter(item -> item.title() != null)
                .filter(item -> date == null || (item.publishedDate() != null && item.publishedDate().equals(date)))
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
