package com.thesisguard.stock;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StockLookupRankingTest {

    @Test
    void exactSymbolMatchRanksFirst() {
        List<StockLookupResponse> raw = List.of(
                new StockLookupResponse("ABBV", "AbbVie Inc."),
                new StockLookupResponse("BBY", "Best Buy Co Inc"),
                new StockLookupResponse("BBVA", "Banco Bilbao Vizcaya Argentaria"),
                new StockLookupResponse("BB", "BlackBerry Limited"));

        List<StockLookupResponse> ranked = StockService.rankByRelevance(raw, "BB");

        assertThat(ranked.get(0).symbol()).isEqualTo("BB");
    }

    @Test
    void prefixMatchesPrecedeSubstringMatches() {
        List<StockLookupResponse> raw = List.of(
                new StockLookupResponse("ABBV", "AbbVie Inc."),
                new StockLookupResponse("BBY", "Best Buy Co Inc"),
                new StockLookupResponse("BB", "BlackBerry Limited"));

        // lower-case query must still rank the exact ticker first (case-insensitive).
        List<StockLookupResponse> ranked = StockService.rankByRelevance(raw, "bb");

        assertThat(ranked).extracting(StockLookupResponse::symbol)
                .containsExactly("BB", "BBY", "ABBV");
    }

    @Test
    void capsAtTwentyResultsAfterRanking() {
        List<StockLookupResponse> raw = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            raw.add(new StockLookupResponse("SYM" + i, "Company " + i));
        }

        assertThat(StockService.rankByRelevance(raw, "SYM")).hasSize(20);
    }
}
