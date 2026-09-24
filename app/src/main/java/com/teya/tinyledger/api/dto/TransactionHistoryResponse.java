package com.teya.tinyledger.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * A page of transaction history returned to API clients, most recent movement first.
 *
 * @param accountId    the account the transactions belong to
 * @param transactions the requested page, newest first
 * @param limit        the page size that was applied
 * @param offset       how many of the most recent transactions were skipped
 * @param total        the total number of transactions on the account
 */
public record TransactionHistoryResponse(
        UUID accountId,
        List<TransactionResponse> transactions,
        int limit,
        int offset,
        int total) {
}
