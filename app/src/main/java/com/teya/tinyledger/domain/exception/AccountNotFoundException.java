package com.teya.tinyledger.domain.exception;

import java.util.UUID;

/**
 * Raised when an operation refers to an account that does not exist.
 */
public class AccountNotFoundException extends LedgerException {

    /** The machine-readable code reported for this failure. */
    public static final String CODE = "ACCOUNT_NOT_FOUND";

    private final UUID accountId;

    /**
     * Creates the exception.
     *
     * @param accountId the identifier that could not be resolved
     */
    public AccountNotFoundException(UUID accountId) {
        super("No account exists with id " + accountId);
        this.accountId = accountId;
    }

    /**
     * @return the identifier that could not be resolved
     */
    public UUID accountId() {
        return accountId;
    }

    @Override
    public String code() {
        return CODE;
    }
}
