package com.teya.tinyledger.domain.exception;

import java.util.UUID;

/**
 * Raised when an {@code Idempotency-Key} is replayed against a movement it was not originally
 * recorded for.
 *
 * <p>A key is remembered the first time it is used, together with the type and amount of the
 * movement it produced. Replaying that same key with the same type and amount is a safe retry
 * and returns the original transaction unchanged &mdash; see
 * {@link com.teya.tinyledger.domain.Account#recordMovement(com.teya.tinyledger.domain.TransactionType,
 * com.teya.tinyledger.domain.Money, String, String)}. Replaying it with a <em>different</em>
 * type or amount means the same key is being reused for two distinct movements, which the
 * caller almost certainly did not intend, so it is refused rather than either silently applied
 * or silently ignored.</p>
 */
public class DuplicateIdempotencyKeyException extends LedgerException {

    /** The machine-readable code reported for this failure. */
    public static final String CODE = "IDEMPOTENCY_KEY_REUSED";

    private final UUID accountId;
    private final String idempotencyKey;

    /**
     * Creates the exception.
     *
     * @param accountId      the account the key was replayed against
     * @param idempotencyKey the key that was reused for a different movement
     */
    public DuplicateIdempotencyKeyException(UUID accountId, String idempotencyKey) {
        super("Idempotency key '%s' was already used on account %s to record a different movement"
                .formatted(idempotencyKey, accountId));
        this.accountId = accountId;
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * @return the account the key was replayed against
     */
    public UUID accountId() {
        return accountId;
    }

    /**
     * @return the key that was reused for a different movement
     */
    public String idempotencyKey() {
        return idempotencyKey;
    }

    @Override
    public String code() {
        return CODE;
    }
}
