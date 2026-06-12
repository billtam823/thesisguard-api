package com.thesisguard.common.exception;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;

public record ErrorResponse(
        LocalDateTime timestamp,
        int status,
        String error,
        String message,
        @JsonProperty("field_errors") List<FieldErrorResponse> fieldErrors
) {
    public static ErrorResponse of(HttpStatus status, String message) {
        return new ErrorResponse(LocalDateTime.now(), status.value(), status.getReasonPhrase(), message, List.of());
    }
}
