package com.teya.tinyledger.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Representation of an account returned to API clients.
 *
 * <p>Monetary values are serialised as strings so that clients parsing JSON with IEEE-754
 * doubles cannot silently lose precision.</p>
 *
 * @param id               unique identifier of the account
 * @param ownerName        name of the account holder
 * @param currency         ISO 4217 code of the account currency
 * @param overdraftLimit   agreed overdraft allowance
 * @param availableBalance sum of every transaction on the account
 * @param accountBalance   available balance plus the overdraft allowance
 * @param openedAt         when the account was opened
 */
public record AccountResponse(
        UUID id,
        String ownerName,
        String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal overdraftLimit,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal availableBalance,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal accountBalance,
        Instant openedAt) {
}
