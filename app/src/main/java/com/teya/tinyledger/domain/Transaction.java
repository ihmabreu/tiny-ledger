package com.teya.tinyledger.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable record of a single money movement against an account.
 *
 * <p>Transactions are append-only: once recorded, a movement is never modified or deleted.
 * Corrections would be expressed as a further, compensating movement.</p>
 *
 * <p>The {@code sequence} is a per-account counter starting at {@code 1}. It defines the
 * account's authoritative transaction order. Wall-clock timestamps are deliberately not used
 * for ordering, because two movements recorded concurrently can share an identical
 * {@link Instant} and clocks are not guaranteed to move forwards.</p>
 *
 * @param id                    unique identifier of this movement
 * @param accountId             the account the movement belongs to
 * @param sequence              the account-scoped ordinal of this movement, starting at 1
 * @param type                  whether money moved in or out
 * @param amount                the strictly positive amount that moved
 * @param availableBalanceAfter the account's available balance immediately after the movement
 * @param reference             an optional free-text note, may be {@code null}
 * @param recordedAt            when the movement was recorded
 */
public record Transaction(
        UUID id,
        UUID accountId,
        long sequence,
        TransactionType type,
        Money amount,
        Money availableBalanceAfter,
        String reference,
        Instant recordedAt) {

    /**
     * Canonical constructor enforcing the invariants every movement must satisfy.
     *
     * @throws NullPointerException     if any required component is {@code null}
     * @throws IllegalArgumentException if the amount is not strictly positive, the sequence is
     *                                  not positive, or the currencies are inconsistent
     */
    public Transaction {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(availableBalanceAfter, "availableBalanceAfter must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");

        if (sequence < 1) {
            throw new IllegalArgumentException("Transaction sequence must be positive, but was " + sequence);
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "Transaction amount must be strictly positive, but was " + amount
                            + ". Use the transaction type to express direction.");
        }
        if (!amount.currency().equals(availableBalanceAfter.currency())) {
            throw new IllegalArgumentException(
                    "Transaction amount currency %s does not match balance currency %s"
                            .formatted(amount.currency().getCurrencyCode(),
                                    availableBalanceAfter.currency().getCurrencyCode()));
        }
    }

    /**
     * Returns the amount with the sign implied by the movement's direction.
     *
     * @return a positive amount for a deposit, a negative amount for a withdrawal
     */
    public Money signedAmount() {
        return type.signed(amount);
    }
}
