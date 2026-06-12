package com.thesisguard.alert;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class AlertController {
    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping("/api/alerts")
    public List<AlertResponse> list() {
        return alertService.list();
    }

    @GetMapping("/api/stocks/{stockCode}/alerts")
    public List<AlertResponse> listByStock(@PathVariable String stockCode) {
        return alertService.listByStock(stockCode);
    }

    @PutMapping("/api/alerts/{alertId}/resolve")
    public AlertResponse resolve(@PathVariable Long alertId) {
        return alertService.resolve(alertId);
    }
}
