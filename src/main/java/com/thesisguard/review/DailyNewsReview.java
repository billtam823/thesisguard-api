package com.thesisguard.review;

import com.thesisguard.stock.Stock;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "daily_news_reviews")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyNewsReview {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Stock stock;

    @Column(nullable = false)
    private LocalDate reviewDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ThesisChangeLevel thesisChangeLevel;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String thesisImpact;

    @Column(columnDefinition = "TEXT")
    private String recommendedAction;

    @OneToMany(mappedBy = "dailyNewsReview", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<NewsAnalysisItem> analysisItems = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public void addAnalysisItem(NewsAnalysisItem item) {
        analysisItems.add(item);
        item.setDailyNewsReview(this);
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (reviewDate == null) {
            reviewDate = LocalDate.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
