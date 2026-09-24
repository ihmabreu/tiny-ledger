package com.teya.tinyledger.repository;

import com.teya.tinyledger.domain.Account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage abstraction for {@link Account} aggregates.
 *
 * <p>The service layer depends on this interface rather than on any concrete store, so the
 * in-memory implementation used by this exercise could be replaced by a database-backed one
 * without the business rules changing. The interface is deliberately tiny: the ledger only
 * ever needs to store an account, look one up, and list them.</p>
 */
public interface AccountRepository {

    /**
     * Stores an account, replacing any previous entry with the same identifier.
     *
     * @param account the account to store
     * @return the stored account
     */
    Account save(Account account);

    /**
     * Looks up an account by its identifier.
     *
     * @param accountId the identifier to resolve
     * @return the account, or {@link Optional#empty()} if no such account exists
     */
    Optional<Account> findById(UUID accountId);

    /**
     * Lists every stored account.
     *
     * @return all accounts, in the order they were opened
     */
    List<Account> findAll();
}
