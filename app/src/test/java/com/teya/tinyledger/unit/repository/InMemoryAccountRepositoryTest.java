package com.teya.tinyledger.unit.repository;

import com.teya.tinyledger.domain.Account;
import com.teya.tinyledger.domain.Money;
import com.teya.tinyledger.repository.AccountRepository;
import com.teya.tinyledger.repository.InMemoryAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link InMemoryAccountRepository}.
 *
 * <p>The repository deliberately does nothing clever &mdash; that is the point. These tests pin
 * the contract that a future persistent implementation would have to honour: store, look up,
 * and list in opening order.</p>
 */
@Tag("unit")
@DisplayName("In-memory account repository")
class InMemoryAccountRepositoryTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC);

    private AccountRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAccountRepository();
    }

    private static Account newAccount(String ownerName) {
        return Account.open(ownerName, EUR, Money.zero(EUR), CLOCK);
    }

    @Test
    @DisplayName("stores an account and returns it")
    void save_validAccount_storesAndReturnsSameInstance() {
        Account account = newAccount("Ada");

        assertThat(repository.save(account)).isSameAs(account);
    }

    @Test
    @DisplayName("finds a stored account by its identifier")
    void findById_storedAccountId_returnsOptionalContainingAccount() {
        Account account = repository.save(newAccount("Ada"));

        assertThat(repository.findById(account.id())).contains(account);
    }

    @Test
    @DisplayName("returns nothing for an unknown identifier")
    void findById_unknownAccountId_returnsEmptyOptional() {
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("starts empty")
    void findAll_emptyRepository_returnsEmptyList() {
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("lists accounts in the order they were stored")
    void findAll_multipleSavedAccounts_returnsInInsertionOrder() {
        Account first = repository.save(newAccount("Ada"));
        Account second = repository.save(newAccount("Grace"));
        Account third = repository.save(newAccount("Alan"));

        assertThat(repository.findAll()).containsExactly(first, second, third);
    }

    @Test
    @DisplayName("hands out an immutable listing")
    void findAll_returnedList_throwsUnsupportedOperationExceptionOnMutation() {
        repository.save(newAccount("Ada"));
        List<Account> accounts = repository.findAll();

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> accounts.remove(0));
    }

    @Test
    @DisplayName("treats a null identifier as simply not found")
    void findById_nullAccountId_returnsEmptyOptional() {
        assertThat(repository.findById(null)).isEmpty();
    }

    @Test
    @DisplayName("rejects storing null")
    void save_nullAccount_throwsNullPointerException() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> repository.save(null));
    }
}
