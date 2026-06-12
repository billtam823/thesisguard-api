package com.thesisguard.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DailyNewsReviewRepository extends JpaRepository<DailyNewsReview, Long> {
    List<DailyNewsReview> findByStockIdOrderByReviewDateDescCreatedAtDesc(Long stockId);
    Optional<DailyNewsReview> findFirstByStockIdOrderByReviewDateDescCreatedAtDesc(Long stockId);
}
