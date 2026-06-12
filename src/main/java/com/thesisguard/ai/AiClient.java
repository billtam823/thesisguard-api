package com.thesisguard.ai;

import com.thesisguard.news.NewsItem;
import com.thesisguard.review.ReviewAiResponse;
import com.thesisguard.stock.Stock;
import com.thesisguard.thesis.StockThesis;
import com.thesisguard.thesis.ThesisAiResponse;

import java.util.List;

public interface AiClient {
    ThesisAiResponse generateBuyThesis(Stock stock);
    ReviewAiResponse reviewNews(Stock stock, StockThesis thesis, List<NewsItem> newsItems);
}
