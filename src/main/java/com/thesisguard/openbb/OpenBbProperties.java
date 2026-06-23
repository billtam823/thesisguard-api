package com.thesisguard.openbb;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "openbb")
public record OpenBbProperties(
        @NotBlank String baseUrl,
        String apiKey
) {
}
