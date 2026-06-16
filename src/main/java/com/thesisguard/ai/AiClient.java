package com.thesisguard.ai;

import com.thesisguard.news.NewsItem;
import com.thesisguard.review.NewsTriageResult;
import com.thesisguard.review.ReviewAiResponse;
import com.thesisguard.stock.Stock;
import com.thesisguard.thesis.StockThesis;
import com.thesisguard.thesis.ThesisAiResponse;

import java.util.List;

public interface AiClient {
    ThesisAiResponse generateBuyThesis(Stock stock);

    /**
     * Cheap, fast pass that classifies each pending news item as material (could affect the
     * long-term thesis) or noise, so only material items reach the expensive doctrine review.
     * Implementations must fail safe: when triage cannot be performed, treat items as material.
     */
    NewsTriageResult triageNews(Stock stock, StockThesis thesis, List<NewsItem> newsItems);

    ReviewAiResponse reviewNews(Stock stock, StockThesis thesis, List<NewsItem> newsItems, String monitorMemory);
}
