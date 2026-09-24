package com.teya.tinyledger.domain.exception;

/**
 * Base type for every business rule violation raised by the ledger domain.
 *
 * <p>Having a single root lets the API layer translate domain failures into HTTP responses
 * exhaustively, while the domain itself stays free of any knowledge about HTTP.</p>
 */
public abstract class LedgerException extends RuntimeException {

    /**
     * Creates a ledger exception.
     *
     * @param message a human-readable explanation of the violated rule
     */
    protected LedgerException(String message) {
        super(message);
    }

    /**
     * A stable, machine-readable code identifying the violated rule.
     *
     * <p>Clients are expected to branch on this code rather than on the message text.</p>
     *
     * @return the error code, for example {@code INSUFFICIENT_FUNDS}
     */
    public abstract String code();
}
