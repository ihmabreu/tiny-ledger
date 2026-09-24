package com.teya.tinyledger.api.mapper;

import com.teya.tinyledger.api.dto.AccountResponse;
import com.teya.tinyledger.api.dto.BalanceResponse;
import com.teya.tinyledger.api.dto.TransactionHistoryResponse;
import com.teya.tinyledger.api.dto.TransactionResponse;
import com.teya.tinyledger.domain.Account;
import com.teya.tinyledger.domain.AccountBalance;
import com.teya.tinyledger.domain.Transaction;
import com.teya.tinyledger.domain.TransactionPage;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Translates domain objects into the representations published by the API.
 *
 * <p>Keeping the translation in one place means the domain model never has to carry
 * serialisation concerns, and the wire format can evolve independently of it &mdash; which is
 * exactly what makes versioning the API practical.</p>
 */
@ApplicationScoped
public class LedgerResponseMapper {

    /**
     * Maps an account, including its current balance.
     *
     * @param account the account to map
     * @return the account representation
     */
    public AccountResponse toAccountResponse(Account account) {
        AccountBalance balance = account.balance();
        return new AccountResponse(
                account.id(),
                account.ownerName(),
                account.currency().getCurrencyCode(),
                account.overdraftLimit().amount(),
                balance.availableBalance().amount(),
                balance.accountBalance().amount(),
                account.openedAt());
    }

    /**
     * Maps every account in a list.
     *
     * @param accounts the accounts to map
     * @return the account representations, preserving order
     */
    public List<AccountResponse> toAccountResponses(List<Account> accounts) {
        return accounts.stream().map(this::toAccountResponse).toList();
    }

    /**
     * Maps a balance.
     *
     * @param balance the balance to map
     * @return the balance representation
     */
    public BalanceResponse toBalanceResponse(AccountBalance balance) {
        return new BalanceResponse(
                balance.accountId(),
                balance.currency().getCurrencyCode(),
                balance.availableBalance().amount(),
                balance.accountBalance().amount(),
                balance.overdraftLimit().amount(),
                balance.transactionCount(),
                balance.calculatedAt());
    }

    /**
     * Maps a single movement.
     *
     * @param transaction the transaction to map
     * @return the transaction representation
     */
    public TransactionResponse toTransactionResponse(Transaction transaction) {
        return new TransactionResponse(
                transaction.id(),
                transaction.accountId(),
                transaction.type(),
                transaction.amount().amount(),
                transaction.amount().currency().getCurrencyCode(),
                transaction.availableBalanceAfter().amount(),
                transaction.reference(),
                transaction.occurredAt(),
                transaction.recordedAt());
    }

    /**
     * Maps a page of history.
     *
     * @param page the page to map
     * @return the history representation, most recent movement first
     */
    public TransactionHistoryResponse toHistoryResponse(TransactionPage page) {
        return new TransactionHistoryResponse(
                page.accountId(),
                page.transactions().stream().map(this::toTransactionResponse).toList(),
                page.limit(),
                page.offset(),
                page.total());
    }
}
