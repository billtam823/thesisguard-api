package com.thesisguard.newsfilter;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "newsfilter")
public record NewsfilterProperties(String baseUrl, String apiKey) {}
