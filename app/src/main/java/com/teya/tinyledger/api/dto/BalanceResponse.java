package com.teya.tinyledger.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Representation of an account's balance returned to API clients.
 *
 * @param accountId        the account the balance belongs to
 * @param currency         ISO 4217 code of the account currency
 * @param availableBalance sum of every transaction on the account
 * @param accountBalance   available balance plus the overdraft allowance
 * @param overdraftLimit   agreed overdraft allowance
 * @param transactionCount how many transactions the balance is derived from
 * @param calculatedAt     when the newest included transaction was recorded, or when the
 *                         account was opened if it has none yet
 */
public record BalanceResponse(
        UUID accountId,
        String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal availableBalance,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal accountBalance,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal overdraftLimit,
        int transactionCount,
        Instant calculatedAt) {
}
