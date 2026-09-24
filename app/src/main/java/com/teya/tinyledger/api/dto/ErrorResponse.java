package com.teya.tinyledger.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * The single error shape used by every failing endpoint.
 *
 * <p>One consistent structure means clients need only one error-handling path, and can branch
 * on the stable {@code code} rather than parsing human-readable text.</p>
 *
 * @param code      stable, machine-readable identifier of what went wrong
 * @param message   human-readable explanation
 * @param timestamp when the failure was produced
 * @param details   field-level messages, present only for validation failures
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        Instant timestamp,
        List<String> details) {

    /**
     * Creates an error without field-level detail.
     *
     * @param code    stable, machine-readable identifier
     * @param message human-readable explanation
     * @return the error body
     */
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, Instant.now(), null);
    }

    /**
     * Creates an error carrying field-level validation messages.
     *
     * @param code    stable, machine-readable identifier
     * @param message human-readable explanation
     * @param details one message per rejected field
     * @return the error body
     */
    public static ErrorResponse of(String code, String message, List<String> details) {
        return new ErrorResponse(code, message, Instant.now(), details);
    }
}
