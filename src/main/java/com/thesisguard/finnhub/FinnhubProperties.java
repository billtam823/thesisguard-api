package com.thesisguard.finnhub;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "finnhub")
public record FinnhubProperties(String apiKey, String baseUrl) {
    public FinnhubProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://finnhub.io/api/v1";
        }
    }
}
