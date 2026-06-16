package com.thesisguard.news;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record NewsItemCreateRequest(
        @NotBlank @Size(max = 500) String title,
        String summary,
        @Size(max = 1000) String url,
        @JsonProperty("published_date") LocalDate publishedDate,
        @Size(max = 64) String source
) {
}
