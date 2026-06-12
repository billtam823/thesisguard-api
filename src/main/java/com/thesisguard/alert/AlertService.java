package com.thesisguard.alert;

import com.thesisguard.common.exception.ResourceNotFoundException;
import com.thesisguard.review.DailyNewsReview;
import com.thesisguard.review.ThesisChangeLevel;
import com.thesisguard.stock.Stock;
import com.thesisguard.stock.StockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AlertService {
    private final AlertRepository alertRepository;
    private final StockService stockService;

    public AlertService(AlertRepository alertRepository, StockService stockService) {
        this.alertRepository = alertRepository;
        this.stockService = stockService;
    }

    @Transactional(readOnly = true)
    public List<AlertResponse> list() {
        return alertRepository.findAllByOrderByCreatedAtDesc().stream().map(AlertResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<AlertResponse> listByStock(String stockCode) {
        Stock stock = stockService.getEntity(stockCode);
        return alertRepository.findByStockIdOrderByCreatedAtDesc(stock.getId()).stream().map(AlertResponse::from).toList();
    }

    @Transactional
    public AlertResponse resolve(Long alertId) {
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("Alert not found: " + alertId));
        alert.setResolved(true);
        alert.setResolvedAt(LocalDateTime.now());
        return AlertResponse.from(alertRepository.save(alert));
    }

    @Transactional
    public Alert createForReview(Stock stock, DailyNewsReview review) {
        Alert alert = Alert.builder()
                .stock(stock)
                .dailyNewsReview(review)
                .severity(toSeverity(review.getThesisChangeLevel()))
                .title(displayCode(stock) + " thesis review requires attention")
                .message(review.getThesisChangeLevel().getLabel() + ": " + review.getSummary())
                .resolved(false)
                .build();
        return alertRepository.save(alert);
    }

    private String displayCode(Stock stock) {
        String ex = stock.getExchange();
        return (ex != null && !ex.isBlank()) ? ex + ": " + stock.getTicker() : stock.getTicker();
    }

    private AlertSeverity toSeverity(ThesisChangeLevel level) {
        return switch (level) {
            case Watch_Change -> AlertSeverity.Watch;
            case Material_Change -> AlertSeverity.Material;
            case Thesis_Broken -> AlertSeverity.Critical;
            default -> AlertSeverity.Watch;
        };
    }
}
