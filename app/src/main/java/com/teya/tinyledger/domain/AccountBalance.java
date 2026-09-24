package com.teya.tinyledger.domain;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/**
 * A reportable view of an account's balance.
 *
 * @param accountId        the account the balance belongs to
 * @param currency         the currency the account is held in
 * @param availableBalance the sum of every transaction on the account
 * @param accountBalance   the available balance plus the agreed overdraft allowance
 * @param overdraftLimit   the agreed overdraft allowance
 * @param transactionCount how many transactions the balance is derived from
 * @param calculatedAt     when the most recent included transaction was recorded, or when the
 *                         account was opened if it has no transactions yet
 */
public record AccountBalance(
        UUID accountId,
        Currency currency,
        Money availableBalance,
        Money accountBalance,
        Money overdraftLimit,
        int transactionCount,
        Instant calculatedAt) {
}
