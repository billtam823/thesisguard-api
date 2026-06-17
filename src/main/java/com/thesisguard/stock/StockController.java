package com.thesisguard.stock;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/watchlist/stocks")
public class StockController {
    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StockResponse create(@Valid @RequestBody StockCreateRequest request) {
        return stockService.create(request);
    }

    @GetMapping
    public List<StockResponse> list() {
        return stockService.list();
    }

    @GetMapping("/lookup")
    public List<StockLookupResponse> lookup(@RequestParam String query) {
        return stockService.lookup(query);
    }

    @GetMapping("/{stockCode}")
    public StockResponse get(@PathVariable String stockCode) {
        return stockService.get(stockCode);
    }

    @DeleteMapping("/{stockCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String stockCode) {
        stockService.delete(stockCode);
    }

    @PostMapping("/{stockCode}/refresh-profile")
    public StockResponse refreshProfile(@PathVariable String stockCode) {
        return stockService.refreshProfile(stockCode);
    }
}