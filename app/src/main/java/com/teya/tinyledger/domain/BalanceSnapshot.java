package com.teya.tinyledger.domain;

import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable, incrementally maintained view of an account's available balance.
 *
 * <p>The available balance is by definition the sum of every transaction on the account.
 * Recomputing that sum from the full history on every read would cost {@code O(n)} and get
 * slower for the account's whole lifetime. Instead each snapshot remembers <em>how far</em>
 * it has consumed the history:</p>
 *
 * <ul>
 *   <li>{@link #availableBalance()} &mdash; the running total,</li>
 *   <li>{@link #lastAppliedSequence()} &mdash; the ordinal of the newest transaction folded in,</li>
 *   <li>{@link #lastAppliedTransactionId()} &mdash; the identity of that transaction,</li>
 *   <li>{@link #lastAppliedAt()} &mdash; when that transaction was recorded.</li>
 * </ul>
 *
 * <p>Folding a new movement is therefore {@code O(1)} (see {@link #fold(Transaction)}), and a
 * stale snapshot can always be brought up to date by replaying only the transactions after
 * its marker (see {@link #foldAll(Iterable)}) rather than the whole history. That same
 * property makes the snapshot auditable: replaying the tail must always reproduce the
 * incrementally maintained value.</p>
 *
 * <p>The overdraft allowance is deliberately <em>not</em> part of this snapshot. It is account
 * metadata, not money that moved, so it never appears in the transaction history and never
 * contributes to the available balance.</p>
 *
 * @param availableBalance         the sum of all transactions folded in so far
 * @param lastAppliedSequence      the sequence of the newest folded transaction, {@code 0} when none
 * @param lastAppliedTransactionId the id of the newest folded transaction, {@code null} when none
 * @param lastAppliedAt            when the newest folded transaction was recorded, or when the
 *                                 account was opened if no transaction has been folded yet
 */
public record BalanceSnapshot(
        Money availableBalance,
        long lastAppliedSequence,
        UUID lastAppliedTransactionId,
        Instant lastAppliedAt) {

    /**
     * Canonical constructor validating the snapshot's internal consistency.
     *
     * @throws NullPointerException     if the balance or timestamp is {@code null}
     * @throws IllegalArgumentException if the sequence is negative, or the sequence and the
     *                                  transaction id disagree about whether anything was folded
     */
    public BalanceSnapshot {
        Objects.requireNonNull(availableBalance, "availableBalance must not be null");
        Objects.requireNonNull(lastAppliedAt, "lastAppliedAt must not be null");

        if (lastAppliedSequence < 0) {
            throw new IllegalArgumentException("lastAppliedSequence must not be negative");
        }
        if ((lastAppliedSequence == 0) != (lastAppliedTransactionId == null)) {
            throw new IllegalArgumentException(
                    "lastAppliedSequence and lastAppliedTransactionId must both indicate the same state");
        }
    }

    /**
     * Creates the snapshot of a freshly opened account: zero balance, nothing folded in.
     *
     * @param currency the account currency
     * @param openedAt when the account was opened
     * @return the opening snapshot
     */
    public static BalanceSnapshot opening(Currency currency, Instant openedAt) {
        return new BalanceSnapshot(Money.zero(currency), 0L, null, openedAt);
    }

    /**
     * Folds a single movement into this snapshot, in constant time.
     *
     * @param transaction the next transaction, which must directly follow this snapshot
     * @return a new snapshot including the transaction
     * @throws IllegalArgumentException if the transaction's currency differs from the balance
     *                                  currency, or it does not directly follow this snapshot
     */
    public BalanceSnapshot fold(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");

        if (!transaction.amount().currency().equals(availableBalance.currency())) {
            throw new IllegalArgumentException(
                    "Cannot fold a %s transaction into a %s balance"
                            .formatted(transaction.amount().currency().getCurrencyCode(),
                                    availableBalance.currency().getCurrencyCode()));
        }
        if (transaction.sequence() != lastAppliedSequence + 1) {
            throw new IllegalArgumentException(
                    "Expected the next transaction to have sequence %d but it had %d"
                            .formatted(lastAppliedSequence + 1, transaction.sequence()));
        }

        return new BalanceSnapshot(
                availableBalance.add(transaction.signedAmount()),
                transaction.sequence(),
                transaction.id(),
                transaction.recordedAt());
    }

    /**
     * Folds an ordered sequence of movements into this snapshot.
     *
     * <p>Used to bring a stale snapshot up to date, and by the audit checks that verify the
     * incrementally maintained balance against a replay of the history tail.</p>
     *
     * @param transactions the transactions to fold, in ascending sequence order
     * @return a new snapshot including every supplied transaction
     * @throws IllegalArgumentException if the transactions are not the contiguous continuation
     *                                  of this snapshot
     */
    public BalanceSnapshot foldAll(Iterable<Transaction> transactions) {
        Objects.requireNonNull(transactions, "transactions must not be null");
        BalanceSnapshot result = this;
        for (Transaction transaction : transactions) {
            result = result.fold(transaction);
        }
        return result;
    }

    /**
     * @return the number of transactions folded into this snapshot
     */
    public int transactionCount() {
        return Math.toIntExact(lastAppliedSequence);
    }

    /**
     * @return {@code true} if at least one transaction has been folded in
     */
    public boolean hasTransactions() {
        return lastAppliedSequence > 0;
    }
}
