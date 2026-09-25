package com.teya.tinyledger.domain;

import com.teya.tinyledger.domain.exception.CurrencyMismatchException;
import com.teya.tinyledger.domain.exception.DuplicateIdempotencyKeyException;
import com.teya.tinyledger.domain.exception.InsufficientFundsException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A ledger account: an append-only transaction history plus the balance derived from it.
 *
 * <h2>Balance model</h2>
 *
 * <p>Two figures are reported, and the distinction matters:</p>
 *
 * <ul>
 *   <li><b>available balance</b> &mdash; {@code sum(transactions)}; the money that actually
 *       moved in and out,</li>
 *   <li><b>account balance</b> &mdash; {@code available balance + overdraft limit}; the total
 *       spending power, including the agreed borrowing allowance.</li>
 * </ul>
 *
 * <p>The overdraft allowance is metadata agreed when the account is opened. It is never
 * recorded as a transaction, so the available balance always equals the exact sum of the
 * history. A movement is accepted while it leaves the account balance non-negative.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>This class is thread-safe, which matters because the service is expected to handle many
 * concurrent requests on virtual threads.</p>
 *
 * <p>Recording a movement is a check-then-act sequence: read the balance, decide whether the
 * overdraft allowance permits it, then append. Those steps must be indivisible, otherwise two
 * concurrent withdrawals could both observe sufficient funds and jointly breach the limit. The
 * aggregate therefore serialises writes on a private {@link ReentrantLock}, which also
 * guarantees the history append and the snapshot update happen together, so the two can never
 * drift apart.</p>
 *
 * <p>{@link ReentrantLock} is used in preference to a {@code synchronized} block because the
 * surrounding request runs on a virtual thread; an explicit lock releases the underlying
 * carrier thread if it ever has to wait, whereas a monitor historically pinned it.</p>
 *
 * <p>Reads of the balance never take the lock: the snapshot is an immutable value held in a
 * {@code volatile} field, so readers observe a consistent, if possibly momentarily older,
 * picture without ever blocking a writer. Callers needing the balance and the history to agree
 * exactly should use {@link #consistentView()}.</p>
 *
 * <h2>Idempotency</h2>
 *
 * <p>Every movement is recorded against a caller-supplied idempotency key. The key is
 * remembered together with the movement's type and amount for the lifetime of the account, and
 * checked under the same lock that guards the balance: replaying a key with the same type and
 * amount returns the original {@link Transaction} without recording anything a second time;
 * replaying it with a different type or amount is refused, since that means the same key was
 * reused for two distinct movements. A movement that is refused by the overdraft check does not
 * consume its key &mdash; nothing was applied, so there is nothing to protect against
 * re-applying, and a caller who tops up funds may retry with the same key.</p>
 */
public final class Account {

    private final UUID id;
    private final String ownerName;
    private final Currency currency;
    private final OverdraftPolicy overdraftPolicy;
    private final Instant openedAt;
    private final Clock clock;

    /** Serialises writes so that check-then-act stays indivisible. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Append-only history, guarded by {@link #lock}. */
    private final List<Transaction> transactions = new ArrayList<>();

    /** Immutable running balance; written under {@link #lock}, read without it. */
    private volatile BalanceSnapshot balanceSnapshot;

    /** Idempotency keys seen so far, guarded by {@link #lock}. */
    private final Map<String, IdempotencyRecord> idempotencyRecords = new HashMap<>();

    private Account(UUID id,
                    String ownerName,
                    Currency currency,
                    OverdraftPolicy overdraftPolicy,
                    Instant openedAt,
                    Clock clock) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.ownerName = requireUsableOwnerName(ownerName);
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        this.overdraftPolicy = Objects.requireNonNull(overdraftPolicy, "overdraftPolicy must not be null");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");

        if (!overdraftPolicy.limit().currency().equals(currency)) {
            throw new CurrencyMismatchException(currency, overdraftPolicy.limit().currency());
        }
        this.balanceSnapshot = BalanceSnapshot.opening(currency, openedAt);
    }

    /**
     * Opens a new account with an agreed overdraft allowance.
     *
     * <p>The account always starts with a zero available balance. Opening funds are added by
     * recording an ordinary deposit, so that the available balance never diverges from the sum
     * of the visible history.</p>
     *
     * @param ownerName      name of the account holder
     * @param currency       the currency the account is held in, fixed for its lifetime
     * @param overdraftLimit the agreed, non-negative overdraft allowance in the account currency
     * @param clock          the clock used to timestamp the account and its movements
     * @return the newly opened account
     * @throws IllegalArgumentException  if the owner name is blank or the limit is negative
     * @throws CurrencyMismatchException if the overdraft limit is in another currency
     */
    public static Account open(String ownerName, Currency currency, Money overdraftLimit, Clock clock) {
        return open(ownerName, currency, new FixedOverdraftLimitPolicy(overdraftLimit), clock);
    }

    /**
     * Opens a new account governed by an explicit overdraft policy.
     *
     * @param ownerName       name of the account holder
     * @param currency        the currency the account is held in, fixed for its lifetime
     * @param overdraftPolicy the policy deciding how far the balance may fall
     * @param clock           the clock used to timestamp the account and its movements
     * @return the newly opened account
     * @throws IllegalArgumentException  if the owner name is blank
     * @throws CurrencyMismatchException if the policy's limit is in another currency
     */
    public static Account open(String ownerName, Currency currency, OverdraftPolicy overdraftPolicy, Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        return new Account(UUID.randomUUID(), ownerName, currency, overdraftPolicy, clock.instant(), clock);
    }

    /**
    /**
     * Records a money movement against this account, timing it as having happened now and under
     * an auto-generated idempotency key.
     *
     * <p>Convenience for callers that have no separate client-supplied event time and are not
     * exposed to network retries &mdash; the seed data and the parts of the test suite that are
     * not about timing or idempotency.</p>
     *
     * @param type      whether money moves in or out
     * @param amount    the strictly positive amount to move, in the account currency
     * @param reference an optional free-text note, may be {@code null} or blank
     * @return the recorded, immutable transaction
     * @throws IllegalArgumentException     if the amount is not strictly positive
     * @throws CurrencyMismatchException    if the amount is in another currency
     * @throws InsufficientFundsException   if the movement would breach the overdraft allowance
     */
    public Transaction recordMovement(TransactionType type, Money amount, String reference) {
        return recordMovement(type, amount, reference, clock.instant(), UUID.randomUUID().toString());
    }

    /**
     * Records a money movement against this account with an explicit event time and an
     * auto-generated idempotency key.
     *
     * <p>Convenience for callers testing client event time without testing idempotency keys.</p>
     *
     * @param type       whether money moves in or out
     * @param amount     the strictly positive amount to move, in the account currency
     * @param reference  an optional free-text note, may be {@code null} or blank
     * @param occurredAt when the client says the movement happened; reference data only
     * @return the recorded, immutable transaction
     * @throws IllegalArgumentException     if the amount is not strictly positive, or
     *                                      {@code occurredAt} lies outside the drift window
     * @throws NullPointerException         if {@code occurredAt} is {@code null}
     * @throws CurrencyMismatchException    if the amount is in another currency
     * @throws InsufficientFundsException   if the movement would breach the overdraft allowance
     */
    public Transaction recordMovement(TransactionType type, Money amount, String reference, Instant occurredAt) {
        return recordMovement(type, amount, reference, occurredAt, UUID.randomUUID().toString());
    }

    /**
     * Records a money movement against this account, protected against being applied twice for
     * the same {@code idempotencyKey}, timing it as having happened now.
     *
     * <p>Convenience for callers testing idempotency without separate client event time.</p>
     *
     * @param type           whether money moves in or out
     * @param amount         the strictly positive amount to move, in the account currency
     * @param reference      an optional free-text note, may be {@code null} or blank
     * @param idempotencyKey a caller-supplied key identifying this movement; replaying the same
     *                       key with the same {@code type} and {@code amount} is a safe retry
     * @return the recorded transaction, or the original transaction if this key, type and
     *         amount were seen before
     * @throws IllegalArgumentException          if the amount is not strictly positive, or the
     *                                            key is blank
     * @throws CurrencyMismatchException         if the amount is in another currency
     * @throws InsufficientFundsException        if the movement would breach the overdraft
     *                                            allowance
     * @throws DuplicateIdempotencyKeyException  if the key was already used for a movement of a
     *                                            different type or amount
     */
    public Transaction recordMovement(TransactionType type, Money amount, String reference, String idempotencyKey) {
        return recordMovement(type, amount, reference, clock.instant(), idempotencyKey);
    }

    /**
     * Records a money movement against this account, protected against being applied twice for
     * the same {@code idempotencyKey} and stamped with the client's {@code occurredAt} event time.
     *
     * <p>Validation that needs no shared state is performed before the lock is taken, keeping
     * the critical section as short as possible. The idempotency check itself must happen
     * under the lock, immediately before the overdraft check: recording whether a key has been
     * seen and applying the movement it describes must be one atomic step, otherwise two
     * concurrent retries carrying the same key could both observe "not seen yet" and both
     * apply &mdash; exactly the race this feature exists to close.</p>
     *
     * <p>{@code occurredAt} is recorded verbatim and otherwise ignored: the movement's position
     * in the history, the running balance and the overdraft decision are all driven by the
     * booking time and the account-scoped sequence, never by the client's clock. Its only
     * constraint is that it must fall within the drift window documented on
     * {@link Transaction}, which is checked when the transaction is constructed.</p>
     *
     * @param type           whether money moves in or out
     * @param amount         the strictly positive amount to move, in the account currency
     * @param reference      an optional free-text note, may be {@code null} or blank
     * @param occurredAt     when the client says the movement happened; reference data only
     * @param idempotencyKey a caller-supplied key identifying this movement; replaying the same
     *                       key with the same {@code type} and {@code amount} is a safe retry
     * @return the recorded transaction, or the original transaction if this key, type and
     *         amount were seen before
     * @throws IllegalArgumentException          if the amount is not strictly positive,
     *                                            {@code occurredAt} lies outside the drift window,
     *                                            or the {@code idempotencyKey} is blank
     * @throws NullPointerException              if {@code occurredAt} is {@code null}
     * @throws CurrencyMismatchException         if the amount is in another currency
     * @throws InsufficientFundsException        if the movement would breach the overdraft
     *                                            allowance
     * @throws DuplicateIdempotencyKeyException  if the key was already used for a movement of a
     *                                            different type or amount
     */
    public Transaction recordMovement(TransactionType type,
                                      Money amount,
                                      String reference,
                                      Instant occurredAt,
                                      String idempotencyKey) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }

        if (!amount.currency().equals(currency)) {
            throw new CurrencyMismatchException(currency, amount.currency());
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "Movement amount must be strictly positive, but was " + amount
                            + ". Use " + TransactionType.WITHDRAWAL + " to move money out.");
        }

        Money signedAmount = type.signed(amount);
        String normalisedReference = normaliseReference(reference);

        lock.lock();
        try {
            IdempotencyRecord existing = idempotencyRecords.get(idempotencyKey);
            if (existing != null) {
                if (existing.type() == type && existing.amount().equals(amount)) {
                    return existing.transaction();
                }
                throw new DuplicateIdempotencyKeyException(id, idempotencyKey);
            }

            BalanceSnapshot current = balanceSnapshot;
            Money resultingBalance = current.availableBalance().add(signedAmount);

            // Only movements that reduce the balance can breach the allowance; a deposit is
            // never refused. Expressed in terms of the resulting balance so that new movement
            // types need no special handling here.
            if (signedAmount.isNegative() && !overdraftPolicy.allows(resultingBalance)) {
                // Deliberately not stored in idempotencyRecords: nothing was applied, so a
                // retry of the same key after the caller tops up funds must be free to try
                // again rather than being permanently refused by a key that only ever failed.
                throw new InsufficientFundsException(
                        id, amount, current.availableBalance(), overdraftPolicy.limit());
            }

            Transaction transaction = new Transaction(
                    UUID.randomUUID(),
                    id,
                    current.lastAppliedSequence() + 1,
                    type,
                    amount,
                    resultingBalance,
                    normalisedReference,
                    occurredAt,
                    clock.instant());

            transactions.add(transaction);
            balanceSnapshot = current.fold(transaction);
            idempotencyRecords.put(idempotencyKey, new IdempotencyRecord(type, amount, transaction));
            return transaction;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current balance snapshot without blocking.
     *
     * @return the immutable running balance
     */
    public BalanceSnapshot balanceSnapshot() {
        return balanceSnapshot;
    }

    /**
     * Returns the account's current balance in reportable form.
     *
     * @return the available balance, the account balance and the overdraft allowance
     */
    public AccountBalance balance() {
        BalanceSnapshot snapshot = balanceSnapshot;
        return new AccountBalance(
                id,
                currency,
                snapshot.availableBalance(),
                snapshot.availableBalance().add(overdraftPolicy.limit()),
                overdraftPolicy.limit(),
                snapshot.transactionCount(),
                snapshot.lastAppliedAt());
    }

    /**
     * Returns a page of the transaction history, most recent movement first.
     *
     * <p>Ordering follows the transaction sequence rather than the wall clock, so the order is
     * stable even for movements recorded in the same instant.</p>
     *
     * @param limit  the maximum number of transactions to return, must be positive
     * @param offset how many of the most recent transactions to skip, must not be negative
     * @return the requested page together with the total number of transactions
     * @throws IllegalArgumentException if {@code limit} is not positive or {@code offset} is negative
     */
    public TransactionPage history(int limit, int offset) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive, but was " + limit);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative, but was " + offset);
        }

        lock.lock();
        try {
            int total = transactions.size();
            List<Transaction> page = new ArrayList<>(Math.min(limit, Math.max(total - offset, 0)));
            for (int index = total - 1 - offset; index >= 0 && page.size() < limit; index--) {
                page.add(transactions.get(index));
            }
            return new TransactionPage(id, List.copyOf(page), limit, offset, total);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the balance and the complete history as they existed at the very same moment.
     *
     * <p>Unlike the lock-free {@link #balanceSnapshot()} read, this takes the write lock, so the
     * two components are guaranteed to agree. Used by the audit checks that replay the history
     * and compare the result against the incrementally maintained snapshot.</p>
     *
     * @return a mutually consistent view of the account
     */
    public ConsistentView consistentView() {
        lock.lock();
        try {
            return new ConsistentView(balanceSnapshot, List.copyOf(transactions));
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return the account's unique identifier
     */
    public UUID id() {
        return id;
    }

    /**
     * @return the name of the account holder
     */
    public String ownerName() {
        return ownerName;
    }

    /**
     * @return the currency the account is held in
     */
    public Currency currency() {
        return currency;
    }

    /**
     * @return the policy deciding how far the balance may fall
     */
    public OverdraftPolicy overdraftPolicy() {
        return overdraftPolicy;
    }

    /**
     * @return the agreed overdraft allowance
     */
    public Money overdraftLimit() {
        return overdraftPolicy.limit();
    }

    /**
     * @return when the account was opened
     */
    public Instant openedAt() {
        return openedAt;
    }

    @Override
    public String toString() {
        return "Account[id=%s, owner=%s, balance=%s]".formatted(id, ownerName, balanceSnapshot.availableBalance());
    }

    private static String requireUsableOwnerName(String ownerName) {
        if (ownerName == null || ownerName.isBlank()) {
            throw new IllegalArgumentException("ownerName must not be blank");
        }
        return ownerName.trim();
    }

    private static String normaliseReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        return reference.trim();
    }

    /**
     * A balance and the history it was derived from, captured atomically.
     *
     * @param balanceSnapshot the running balance at the moment of capture
     * @param transactions    the full history at the moment of capture, oldest first
     */
    public record ConsistentView(BalanceSnapshot balanceSnapshot, List<Transaction> transactions) {
    }

    /**
     * The fingerprint of a movement recorded under a given idempotency key, together with the
     * transaction it produced.
     *
     * <p>{@code amount} is compared with {@link Money#equals(Object)}, which already accounts
     * for currency &mdash; every {@code Money} in this domain is constructed through
     * {@link Money#of}, which normalises scale to the currency's minor unit, so two equal-value
     * amounts are guaranteed to compare equal here with no scale surprises.</p>
     *
     * @param type        the direction of the originally recorded movement
     * @param amount      the amount of the originally recorded movement
     * @param transaction the transaction that was produced
     */
    private record IdempotencyRecord(TransactionType type, Money amount, Transaction transaction) {
    }
}
