package com.teya.tinyledger.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.teya.tinyledger.domain.TransactionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request body for recording a money movement.
 *
 * <p>The amount is always supplied as a positive number; the direction comes from
 * {@code type}. That avoids ambiguity about what a negative withdrawal would mean.</p>
 *
 * @param type      whether money moves in ({@code DEPOSIT}) or out ({@code WITHDRAWAL})
 * @param amount    the strictly positive amount to move
 * @param currency  ISO 4217 code, which must match the account currency
 * @param reference optional free-text note describing the movement
 */
public record RecordTransactionRequest(
        @NotNull(message = "type must be either DEPOSIT or WITHDRAWAL")
        TransactionType type,

        @NotNull(message = "amount must be provided")
        @Positive(message = "amount must be strictly positive")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal amount,

        @NotNull(message = "currency must be provided")
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "currency must be a three-letter ISO 4217 code")
        String currency,

        @Size(max = 140, message = "reference must be at most 140 characters")
        String reference) {
}
