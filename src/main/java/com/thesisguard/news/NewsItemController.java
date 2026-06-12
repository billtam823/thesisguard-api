package com.thesisguard.news;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/stocks/{stockCode}/news")
public class NewsItemController {
    private final NewsItemService newsItemService;

    public NewsItemController(NewsItemService newsItemService) {
        this.newsItemService = newsItemService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NewsItemResponse create(@PathVariable String stockCode, @Valid @RequestBody NewsItemCreateRequest request) {
        return newsItemService.create(stockCode, request);
    }

    @GetMapping
    public List<NewsItemResponse> list(@PathVariable String stockCode) {
        return newsItemService.list(stockCode);
    }

    @GetMapping("/today")
    public List<NewsItemResponse> today(@PathVariable String stockCode) {
        return newsItemService.today(stockCode);
    }

    @GetMapping("/fetch")
    public List<FetchedNewsItemResponse> previewNews(
            @PathVariable String stockCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return newsItemService.previewNews(stockCode, date);
    }

    @GetMapping("/filings")
    public List<FetchedNewsItemResponse> previewFilings(
            @PathVariable String stockCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return newsItemService.previewFilings(stockCode, date);
    }

    @GetMapping("/insider")
    public List<FetchedNewsItemResponse> previewInsiderTrades(
            @PathVariable String stockCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return newsItemService.previewInsiderTrades(stockCode, date);
    }

}
