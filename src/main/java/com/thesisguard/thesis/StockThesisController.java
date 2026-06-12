package com.thesisguard.thesis;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/stocks/{stockCode}")
public class StockThesisController {
    private final StockThesisService thesisService;

    public StockThesisController(StockThesisService thesisService) {
        this.thesisService = thesisService;
    }

    @PostMapping("/generate-thesis")
    public StockThesisResponse generate(@PathVariable String stockCode) {
        return thesisService.generate(stockCode);
    }

    @GetMapping("/thesis")
    public StockThesisResponse get(@PathVariable String stockCode) {
        return thesisService.get(stockCode);
    }

    @PutMapping("/thesis")
    public StockThesisResponse update(@PathVariable String stockCode, @Valid @RequestBody StockThesisUpdateRequest request) {
        return thesisService.update(stockCode, request);
    }
}
