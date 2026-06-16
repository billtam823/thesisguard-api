package com.thesisguard.news;

import com.thesisguard.stock.Stock;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Entity
@Table(name = "news_items", uniqueConstraints = @UniqueConstraint(name = "uk_news_items_stock_dedup", columnNames = {"stock_id", "dedup_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Stock stock;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String summary;

    // Full article text (when the source provides it, e.g. Seeking Alpha). The daily review reads
    // this; summary stays a short blurb for the feed.
    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(length = 1000)
    private String url;

    @Column(nullable = false)
    private LocalDate publishedDate;

    @Column(length = 64)
    private String source;

    private LocalDateTime reviewedAt;

    // Per-item triage/review outcome, written back when a review covers this item, so the
    // news feed can show the noise/material classification inline without joining reviews.
    @Column(length = 32)
    private String impactLevel;

    // Whether the review judged this item to actually be about this stock. Null until reviewed;
    // false marks off-topic items (market-wide/macro roundups, articles about other tickers).
    private Boolean relatedToStock;

    @Column(columnDefinition = "TEXT")
    private String analysis;

    @Column(name = "dedup_key", length = 64)
    private String dedupKey;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (publishedDate == null) {
            publishedDate = LocalDate.now();
        }
        if (dedupKey == null) {
            dedupKey = computeDedupKey(url, title);
        }
    }

    // Same identity rule as the frontend savedKey: insider trades from one Form 4
    // filing share a URL, so the key must combine url and title.
    public static String computeDedupKey(String url, String title) {
        String normalized = (url == null ? "" : url.trim()) + "||" + (title == null ? "" : title.trim().toLowerCase());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
