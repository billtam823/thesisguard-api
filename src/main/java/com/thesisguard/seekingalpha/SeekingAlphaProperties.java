package com.thesisguard.seekingalpha;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "seekingalpha")
public record SeekingAlphaProperties(String host, String apiKey) {}
