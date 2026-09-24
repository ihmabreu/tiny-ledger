package com.teya.tinyledger.domain;

import java.time.Duration;
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
 * <h2>Two timestamps, one of them authoritative</h2>
 *
 * <p>A movement carries two times, and the distinction is the whole point:</p>
 *
 * <ul>
 *   <li>{@code recordedAt} &mdash; <b>booking time</b>, stamped by the ledger's own clock at
 *       the moment the movement was applied. This is the authoritative time.</li>
 *   <li>{@code occurredAt} &mdash; <b>event time</b>, supplied by the client to say when the
 *       movement happened at their end (the card was tapped, the button was pressed). This is
 *       reference data only.</li>
 * </ul>
 *
 * <p>{@code occurredAt} is never used for anything the ledger's correctness depends on: not for
 * ordering, not for the running balance, not for the overdraft decision. A client clock cannot
 * be trusted &mdash; it drifts, it is subject to time-zone and daylight-saving mistakes, and on
 * an open API it can simply be forged to backdate a movement. Letting it drive the ledger would
 * mean a caller could reorder history. It is kept because it answers a genuine question the
 * booking time cannot ("when did this actually happen to the customer?"), particularly for
 * movements captured offline and uploaded later.</p>
 *
 * <p>It is nonetheless sanity-checked, because a value that is wrong by years is far more
 * likely to be a bug than a fact. The accepted window, enforced here so that no caller can
 * construct a movement that violates it, is {@link #MAX_CLOCK_DRIFT_BEHIND} before and
 * {@link #MAX_CLOCK_DRIFT_AHEAD} after {@code recordedAt}. The window is deliberately
 * asymmetric: arriving late is ordinary (a queued retry, a reconnecting offline terminal),
 * whereas a movement claiming to have happened in the future is never legitimate and the only
 * tolerance needed ahead of the clock is for ordinary client-server skew.</p>
 *
 * @param id                    unique identifier of this movement
 * @param accountId             the account the movement belongs to
 * @param sequence              the account-scoped ordinal of this movement, starting at 1
 * @param type                  whether money moved in or out
 * @param amount                the strictly positive amount that moved
 * @param availableBalanceAfter the account's available balance immediately after the movement
 * @param reference             an optional free-text note, may be {@code null}
 * @param occurredAt            when the client says the movement happened; reference data only
 * @param recordedAt            when the ledger applied the movement; the authoritative time
 */
public record Transaction(
        UUID id,
        UUID accountId,
        long sequence,
        TransactionType type,
        Money amount,
        Money availableBalanceAfter,
        String reference,
        Instant occurredAt,
        Instant recordedAt) {

    /** How far before the booking time a client may claim a movement happened. */
    public static final Duration MAX_CLOCK_DRIFT_BEHIND = Duration.ofHours(24);

    /** How far after the booking time a client may claim a movement happened. */
    public static final Duration MAX_CLOCK_DRIFT_AHEAD = Duration.ofMinutes(5);

    /**
     * Canonical constructor enforcing the invariants every movement must satisfy.
     *
     * @throws NullPointerException     if any required component is {@code null}
     * @throws IllegalArgumentException if the amount is not strictly positive, the sequence is
     *                                  not positive, the currencies are inconsistent, or
     *                                  {@code occurredAt} falls outside the accepted drift
     *                                  window around {@code recordedAt}
     */
    public Transaction {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(availableBalanceAfter, "availableBalanceAfter must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");

        if (sequence < 1) {
            throw new IllegalArgumentException("Transaction sequence must be positive, but was " + sequence);
        }
        requireWithinDriftWindow(occurredAt, recordedAt);
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

    /**
     * Rejects a client-supplied event time that is implausibly far from the booking time.
     *
     * @param occurredAt when the client says the movement happened
     * @param recordedAt when the ledger applied it
     * @throws IllegalArgumentException if the gap exceeds the accepted window
     */
    private static void requireWithinDriftWindow(Instant occurredAt, Instant recordedAt) {
        Instant earliest = recordedAt.minus(MAX_CLOCK_DRIFT_BEHIND);
        Instant latest = recordedAt.plus(MAX_CLOCK_DRIFT_AHEAD);

        if (occurredAt.isBefore(earliest)) {
            throw new IllegalArgumentException(
                    "occurredAt %s is more than %s before the ledger's own clock (%s). A movement this old cannot be accepted; check the client's clock."
                            .formatted(occurredAt, describe(MAX_CLOCK_DRIFT_BEHIND), recordedAt));
        }
        if (occurredAt.isAfter(latest)) {
            throw new IllegalArgumentException(
                    "occurredAt %s is more than %s ahead of the ledger's own clock (%s). A movement cannot happen in the future; check the client's clock."
                            .formatted(occurredAt, describe(MAX_CLOCK_DRIFT_AHEAD), recordedAt));
        }
    }

    private static String describe(Duration duration) {
        return duration.toHours() > 0
                ? duration.toHours() + "h"
                : duration.toMinutes() + "m";
    }
}
