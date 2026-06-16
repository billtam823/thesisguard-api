package com.thesisguard.news;

import com.thesisguard.stock.Stock;
import com.thesisguard.stock.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.TimeZone;

/**
 * Auto-fetches news on a configurable schedule (e.g. pre-market and lunch). This only
 * INGESTS — it never runs a review — so news accumulates in the backlog for a manual review.
 * Registers one cron trigger per configured expression; an empty schedule disables the job.
 */
@Component
public class NewsFetchScheduler implements SchedulingConfigurer {
    private static final Logger log = LoggerFactory.getLogger(NewsFetchScheduler.class);

    private final StockRepository stockRepository;
    private final NewsItemService newsItemService;
    private final NewsFetchProperties properties;

    public NewsFetchScheduler(StockRepository stockRepository, NewsItemService newsItemService, NewsFetchProperties properties) {
        this.stockRepository = stockRepository;
        this.newsItemService = newsItemService;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        TimeZone timeZone = TimeZone.getTimeZone(properties.zone());
        for (String cron : properties.crons()) {
            if (cron == null || cron.isBlank()) {
                continue;
            }
            registrar.addCronTask(new CronTask(this::fetchAllStocks, new CronTrigger(cron.trim(), timeZone)));
            log.info("[NewsFetch] Scheduled auto-fetch at cron '{}' ({})", cron.trim(), timeZone.getID());
        }
    }

    // One bad ticker is logged and skipped so the rest of the watchlist still ingests.
    void fetchAllStocks() {
        List<Stock> stocks = stockRepository.findAll();
        log.info("[NewsFetch] Auto-fetch starting for {} stock(s)", stocks.size());
        int totalInserted = 0;
        for (Stock stock : stocks) {
            try {
                totalInserted += newsItemService.ingestLatest(stock);
            } catch (Exception ex) {
                log.warn("[NewsFetch] Skipping {}: {}", stock.getTicker(), ex.getMessage());
            }
        }
        log.info("[NewsFetch] Auto-fetch saved {} new item(s)", totalInserted);
    }
}
