package com.thesisguard.stock;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Set;

@Entity
@Table(name = "stocks", uniqueConstraints = @UniqueConstraint(name = "uk_stocks_ticker", columnNames = "ticker"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Stock {

    private static final Set<String> US_EXCHANGES = Set.of("NASDAQ", "NYSE", "AMEX", "NYSE ARCA");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String ticker;

    @Column(nullable = true, length = 20)
    private String exchange;

    @Column(nullable = true, length = 100)
    private String sector;

    @Column(nullable = true, length = 255)
    private String industry;

    @Column(nullable = false, length = 255)
    private String companyName;

    public String getProviderTicker() {
        if (exchange == null || exchange.isBlank()) return ticker;
        return US_EXCHANGES.contains(exchange.toUpperCase()) ? ticker : ticker + ":" + exchange;
    }

    public boolean isUsListed() {
        return exchange == null || exchange.isBlank() || US_EXCHANGES.contains(exchange.toUpperCase());
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StockStatus status;

    // Set to RUNNING while an async review is running; FAILED on error; null when idle.
    @Column(length = 16)
    private String reviewStatus;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = StockStatus.Hold;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
