package com.teya.tinyledger.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.teya.tinyledger.domain.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Representation of a recorded money movement returned to API clients.
 *
 * @param id                    unique identifier of the movement
 * @param accountId             the account the movement belongs to
 * @param type                  whether money moved in or out
 * @param amount                the positive amount that moved
 * @param currency              ISO 4217 code of the account currency
 * @param availableBalanceAfter the available balance immediately after the movement
 * @param reference             optional free-text note, {@code null} when none was supplied
 * @param recordedAt            when the movement was recorded
 */
public record TransactionResponse(
        UUID id,
        UUID accountId,
        TransactionType type,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal availableBalanceAfter,
        String reference,
        Instant recordedAt) {
}
