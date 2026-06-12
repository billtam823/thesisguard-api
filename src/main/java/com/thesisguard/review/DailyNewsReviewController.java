package com.thesisguard.review;

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
    public DailyNewsReviewResponse reviewToday(@PathVariable String stockCode) {
        return reviewService.reviewToday(stockCode);
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
