package com.teya.tinyledger.domain;

import java.util.List;
import java.util.UUID;

/**
 * A page of an account's transaction history, ordered most recent movement first.
 *
 * @param accountId    the account the transactions belong to
 * @param transactions the requested page, newest first
 * @param limit        the maximum page size that was requested
 * @param offset       how many of the most recent transactions were skipped
 * @param total        the total number of transactions on the account
 */
public record TransactionPage(
        UUID accountId,
        List<Transaction> transactions,
        int limit,
        int offset,
        int total) {

    /**
     * Canonical constructor defensively copying the page contents.
     */
    public TransactionPage {
        transactions = List.copyOf(transactions);
    }
}
