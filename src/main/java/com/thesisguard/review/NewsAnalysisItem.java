package com.thesisguard.review;

import com.thesisguard.news.NewsItem;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "news_analysis_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsAnalysisItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "daily_news_review_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private DailyNewsReview dailyNewsReview;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "news_item_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private NewsItem newsItem;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String analysis;

    @Column(nullable = false, length = 64)
    private String impactLevel;
}
