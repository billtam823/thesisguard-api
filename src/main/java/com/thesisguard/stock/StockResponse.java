package com.thesisguard.stock;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public record StockResponse(
        Long id,
        @JsonProperty("stock_code") String stockCode,
        String exchange,
        @JsonProperty("provider_ticker") String providerTicker,
        @JsonProperty("company_name") String companyName,
        StockStatus status,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt
) {
    public static StockResponse from(Stock stock) {
        return new StockResponse(
                stock.getId(),
                stock.getTicker(),
                stock.getExchange(),
                stock.getProviderTicker(),
                stock.getCompanyName(),
                stock.getStatus(),
                stock.getCreatedAt(),
                stock.getUpdatedAt()
        );
    }
}
