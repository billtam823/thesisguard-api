package com.thesisguard.review;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public record ThesisMonitorMemoryResponse(
        Long id,
        @JsonProperty("stock_id") Long stockId,
        @JsonProperty("memory_text") String memoryText,
        @JsonProperty("previous_memory_text") String previousMemoryText,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt
) {
    public static ThesisMonitorMemoryResponse from(ThesisMonitorMemory memory) {
        return new ThesisMonitorMemoryResponse(memory.getId(), memory.getStock().getId(), memory.getMemoryText(), memory.getPreviousMemoryText(), memory.getCreatedAt(), memory.getUpdatedAt());
    }
}
