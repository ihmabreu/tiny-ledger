package com.teya.tinyledger.service;

import com.teya.tinyledger.domain.Account;
import com.teya.tinyledger.domain.AccountBalance;
import com.teya.tinyledger.domain.Money;
import com.teya.tinyledger.domain.Transaction;
import com.teya.tinyledger.domain.TransactionPage;
import com.teya.tinyledger.domain.TransactionType;
import com.teya.tinyledger.domain.exception.AccountNotFoundException;
import com.teya.tinyledger.repository.AccountRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Default {@link LedgerService}, orchestrating the domain model and the account store.
 *
 * <p>The service intentionally holds no business rules of its own beyond resolving accounts
 * and normalising paging parameters. Deciding whether a movement is permitted lives in the
 * {@link Account} aggregate, because only the aggregate can evaluate and apply that decision
 * atomically. Duplicating the check here would invite exactly the race the aggregate exists to
 * prevent.</p>
 *
 * <p>No locking appears in this class for the same reason: the aggregate owns its own
 * concurrency control.</p>
 */
@ApplicationScoped
public class DefaultLedgerService implements LedgerService {

    private final AccountRepository accountRepository;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param accountRepository the account store
     * @param clock             the clock used to timestamp accounts and movements
     */
    @Inject
    public DefaultLedgerService(AccountRepository accountRepository, Clock clock) {
        this.accountRepository = Objects.requireNonNull(accountRepository, "accountRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Account openAccount(String ownerName, Currency currency, Money overdraftLimit) {
        Objects.requireNonNull(currency, "currency must not be null");
        Money limit = overdraftLimit == null ? Money.zero(currency) : overdraftLimit;
        return accountRepository.save(Account.open(ownerName, currency, limit, clock));
    }

    @Override
    public Account getAccount(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    @Override
    public List<Account> listAccounts() {
        return accountRepository.findAll();
    }

    @Override
    public Transaction deposit(UUID accountId, Money amount, String reference) {
        return recordMovement(accountId, TransactionType.DEPOSIT, amount, reference);
    }

    @Override
    public Transaction withdraw(UUID accountId, Money amount, String reference) {
        return recordMovement(accountId, TransactionType.WITHDRAWAL, amount, reference);
    }

    @Override
    public Transaction recordMovement(UUID accountId, TransactionType type, Money amount, String reference) {
        return getAccount(accountId).recordMovement(type, amount, reference);
    }

    @Override
    public Transaction recordMovement(UUID accountId,
                                      TransactionType type,
                                      Money amount,
                                      String reference,
                                      Instant occurredAt) {
        return getAccount(accountId).recordMovement(type, amount, reference, occurredAt);
    }

    @Override
    public AccountBalance getBalance(UUID accountId) {
        return getAccount(accountId).balance();
    }

    @Override
    public TransactionPage getHistory(UUID accountId, Integer limit, Integer offset) {
        int effectiveLimit = limit == null ? DEFAULT_PAGE_SIZE : limit;
        int effectiveOffset = offset == null ? 0 : offset;

        if (effectiveLimit < 1 || effectiveLimit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and %d, but was %d".formatted(MAX_PAGE_SIZE, effectiveLimit));
        }
        if (effectiveOffset < 0) {
            throw new IllegalArgumentException("offset must not be negative, but was " + effectiveOffset);
        }

        return getAccount(accountId).history(effectiveLimit, effectiveOffset);
    }
}
