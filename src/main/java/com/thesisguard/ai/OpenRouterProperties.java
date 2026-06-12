package com.thesisguard.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openrouter")
public record OpenRouterProperties(String apiKey, String model, String reviewModel) {}
