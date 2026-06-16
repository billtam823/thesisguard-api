package com.thesisguard.openbb;

import com.thesisguard.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Component
public class OpenBbClient {

    private static final Map<String, String> EXCHANGE_CODE_MAP = Map.of(
            "NMS", "NASDAQ",
            "NGM", "NASDAQ",
            "NCM", "NASDAQ",
            "NYQ", "NYSE",
            "ASE", "AMEX",
            "PCX", "NYSE ARCA"
    );

    // OpenBB's yfinance news provider defaults to 10 results when no limit is sent.
    private static final int NEWS_FETCH_LIMIT = 100;

    private final RestClient restClient;

    public OpenBbClient(OpenBbProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .build();
    }

    public String fetchExchange(String symbol) {
        try {
            OpenBbEquityProfileResponse response = restClient.get()
                    .uri(b -> b.path("/api/v1/equity/profile")
                            .queryParam("symbol", symbol)
                            .queryParam("provider", "yfinance")
                            .build())
                    .retrieve()
                    .body(OpenBbEquityProfileResponse.class);

            if (response == null || response.results() == null || response.results().isEmpty()) {
                return null;
            }
            String raw = response.results().get(0).stockExchange();
            if (raw == null || raw.isBlank()) return null;
            return EXCHANGE_CODE_MAP.getOrDefault(raw.toUpperCase(), raw.toUpperCase());
        } catch (Exception ex) {
            return null;
        }
    }

    // US-listed equity search via the SEC provider (no API key needed); returns symbol + name.
    public List<OpenBbEquitySearchItem> searchEquities(String query) {
        try {
            OpenBbEquitySearchResponse response = restClient.get()
                    .uri(b -> b.path("/api/v1/equity/search")
                            .queryParam("query", query)
                            .queryParam("provider", "sec")
                            .build())
                    .retrieve()
                    .body(OpenBbEquitySearchResponse.class);

            if (response == null || response.results() == null) {
                return List.of();
            }
            return response.results();
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to search equities from OpenBB: " + ex.getMessage());
        }
    }

    // Company news via the yfinance provider. Kept for reference/fallback only — yfinance caps at
    // ~10 recent items and ignores limit/date, so the app sources company news from Finnhub instead.
    public List<OpenBbNewsItem> fetchCompanyNews(String symbol, LocalDate date) {
        try {
            OpenBbNewsResponse response = restClient.get()
                    .uri(b -> {
                        var builder = b.path("/api/v1/news/company")
                                .queryParam("symbol", symbol)
                                .queryParam("provider", "yfinance")
                                .queryParam("limit", NEWS_FETCH_LIMIT);
                        if (date != null) {
                            builder = builder
                                    .queryParam("start_date", date.toString())
                                    .queryParam("end_date", date.toString());
                        }
                        return builder.build();
                    })
                    .retrieve()
                    .body(OpenBbNewsResponse.class);

            if (response == null || response.results() == null) {
                return List.of();
            }
            return response.results();
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to fetch news from OpenBB: " + ex.getMessage());
        }
    }

    public List<OpenBbFilingItem> fetchCompanyFilings(String symbol, String formType, int limit) {
        try {
            OpenBbFilingsResponse response = restClient.get()
                    .uri(b -> b.path("/api/v1/equity/fundamental/filings")
                            .queryParam("symbol", symbol)
                            .queryParam("provider", "sec")
                            .queryParam("form_type", formType)
                            .queryParam("limit", limit)
                            .build())
                    .retrieve()
                    .body(OpenBbFilingsResponse.class);

            if (response == null || response.results() == null) {
                return List.of();
            }
            return response.results();
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to fetch SEC filings from OpenBB: " + ex.getMessage());
        }
    }

    public List<OpenBbInsiderTransaction> fetchInsiderTrading(String symbol, LocalDate startDate, LocalDate endDate, int limit) {
        try {
            OpenBbInsiderTradingResponse response = restClient.get()
                    .uri(b -> {
                        var builder = b.path("/api/v1/equity/ownership/insider_trading")
                                .queryParam("symbol", symbol)
                                .queryParam("provider", "sec")
                                .queryParam("limit", limit);
                        if (startDate != null) {
                            builder = builder.queryParam("start_date", startDate.toString());
                        }
                        if (endDate != null) {
                            builder = builder.queryParam("end_date", endDate.toString());
                        }
                        return builder.build();
                    })
                    .retrieve()
                    .body(OpenBbInsiderTradingResponse.class);

            if (response == null || response.results() == null) {
                return List.of();
            }
            return response.results();
        } catch (ApiException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            // OpenBB's SEC provider answers 400 "No Form 4 data was returned for X."
            // when the date range simply has no transactions — that is an empty
            // result, not an upstream failure.
            if (ex.getStatusCode().is4xxClientError()
                    && ex.getResponseBodyAsString().toLowerCase().contains("no form 4 data")) {
                return List.of();
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to fetch insider trading from OpenBB: " + ex.getMessage());
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to fetch insider trading from OpenBB: " + ex.getMessage());
        }
    }
}