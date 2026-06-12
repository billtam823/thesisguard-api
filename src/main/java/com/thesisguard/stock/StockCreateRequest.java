package com.thesisguard.stock;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StockCreateRequest(
        @NotBlank @Size(max = 16) String ticker,
        @NotBlank @Size(max = 255) String companyName,
        @Size(max = 20) String exchange
) {
}
