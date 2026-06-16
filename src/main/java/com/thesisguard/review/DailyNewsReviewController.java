package com.thesisguard.review;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stocks/{stockCode}")
public class DailyNewsReviewController {
    private final DailyNewsReviewService reviewService;

    public DailyNewsReviewController(DailyNewsReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping("/review-news")
    public DailyNewsReviewResponse reviewPending(@PathVariable String stockCode) {
        return reviewService.reviewPending(stockCode);
    }

    @PostMapping("/reviews/auto")
    public ResponseEntity<AutoReviewResponse> autoReview(@PathVariable String stockCode) {
        return ResponseEntity.accepted().body(reviewService.autoReview(stockCode));
    }

    @GetMapping("/monitor-memory")
    public ThesisMonitorMemoryResponse memory(@PathVariable String stockCode) {
        return reviewService.getMemory(stockCode);
    }

    @GetMapping("/daily-reviews")
    public List<DailyNewsReviewResponse> list(@PathVariable String stockCode) {
        return reviewService.list(stockCode);
    }

    @GetMapping("/daily-reviews/latest")
    public DailyNewsReviewResponse latest(@PathVariable String stockCode) {
        return reviewService.latest(stockCode);
    }
}
