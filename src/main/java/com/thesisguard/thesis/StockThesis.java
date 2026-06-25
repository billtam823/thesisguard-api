package com.thesisguard.thesis;

import com.thesisguard.stock.Stock;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

@Entity
@Table(name = "stock_theses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockThesis {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Stock stock;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String fullBuyThesis;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String savedBuyThesisSummary;

    @Column(nullable = false, length = 64)
    private String finalRating;

    @Column(nullable = false, length = 64)
    private String conviction;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String portfolioRole;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String coreThesis;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String businessEssence;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String growthDrivers;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String moatSummary;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String financialQuality;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String valuationView;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String mainRisks;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String thesisBreakTriggers;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String dailyReviewFocus;

    // 5-7 year return forecast bucket (e.g. "5-10x") and the derivation behind it. AI-only,
    // nullable because the compact fallback and older theses omit it.
    @Column(length = 16)
    private String returnMultiple;

    @Column(columnDefinition = "TEXT")
    private String returnBasis;

    // How to own it: suggested position size, accumulation strategy, and upgrade/downgrade
    // conditions. Nullable so ddl-auto=update can add it to existing tables without a default.
    @Column(columnDefinition = "TEXT")
    private String positionGuidance;

    // Set to RUNNING while AI is generating asynchronously; DONE on success; FAILED on error.
    @Column(length = 16)
    private String generationStatus;

    @Column(columnDefinition = "TEXT")
    private String generationError;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
