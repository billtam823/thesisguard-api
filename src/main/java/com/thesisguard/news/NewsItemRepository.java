package com.thesisguard.news;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface NewsItemRepository extends JpaRepository<NewsItem, Long> {
    List<NewsItem> findByStockIdOrderByPublishedDateDescCreatedAtDesc(Long stockId);
    List<NewsItem> findByStockIdAndPublishedDateOrderByCreatedAtDesc(Long stockId, LocalDate publishedDate);
    List<NewsItem> findByStockIdAndReviewedAtIsNullOrderByPublishedDateAscCreatedAtAsc(Long stockId);
    Optional<NewsItem> findByStockIdAndDedupKey(Long stockId, String dedupKey);
}
