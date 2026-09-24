package com.teya.tinyledger.repository;

import com.teya.tinyledger.domain.Account;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory {@link AccountRepository} backed by a {@link ConcurrentHashMap}.
 *
 * <p>The assignment asks for in-memory storage, so accounts live for as long as the process
 * does and are lost on restart. This is called out in {@code docs/ASSUMPTIONS.md}.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>The map handles concurrent registration and lookup of accounts. It deliberately does
 * <em>not</em> coordinate the mutation of an individual account: an {@link Account} guards its
 * own balance and history, because the invariant being protected (never breach the overdraft
 * allowance) belongs to the account, not to the store that happens to hold it. Locking per
 * account rather than across the whole repository also means movements on unrelated accounts
 * proceed in parallel.</p>
 */
@ApplicationScoped
public class InMemoryAccountRepository implements AccountRepository {

    private final Map<UUID, Account> accountsById = new ConcurrentHashMap<>();

    /**
     * Registration order, maintained separately because a hash map has none.
     *
     * <p>Ordering by the opening timestamp instead would be ambiguous: two accounts opened in
     * the same instant &mdash; entirely possible, and guaranteed when tests pin the clock
     * &mdash; would have no defined order at all.</p>
     */
    private final List<UUID> registrationOrder = new CopyOnWriteArrayList<>();

    @Override
    public Account save(Account account) {
        Objects.requireNonNull(account, "account must not be null");
        if (accountsById.put(account.id(), account) == null) {
            registrationOrder.add(account.id());
        }
        return account;
    }

    @Override
    public Optional<Account> findById(UUID accountId) {
        if (accountId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(accountsById.get(accountId));
    }

    @Override
    public List<Account> findAll() {
        return registrationOrder.stream()
                .map(accountsById::get)
                .filter(Objects::nonNull)
                .toList();
    }
}
