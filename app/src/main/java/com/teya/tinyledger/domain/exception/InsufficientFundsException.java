package com.teya.tinyledger.domain.exception;

import com.teya.tinyledger.domain.Money;

import java.util.UUID;

/**
 * Raised when a withdrawal would take an account beyond its agreed overdraft allowance.
 */
public class InsufficientFundsException extends LedgerException {

    /** The machine-readable code reported for this failure. */
    public static final String CODE = "INSUFFICIENT_FUNDS";

    private final UUID accountId;
    private final Money requestedAmount;
    private final Money availableBalance;
    private final Money overdraftLimit;

    /**
     * Creates the exception.
     *
     * @param accountId        the account the withdrawal was attempted against
     * @param requestedAmount  the amount that was requested
     * @param availableBalance the available balance before the withdrawal
     * @param overdraftLimit   the agreed overdraft allowance
     */
    public InsufficientFundsException(UUID accountId,
                                      Money requestedAmount,
                                      Money availableBalance,
                                      Money overdraftLimit) {
        super("Withdrawal of %s would exceed the funds available on account %s (available balance %s, overdraft limit %s)"
                .formatted(requestedAmount, accountId, availableBalance, overdraftLimit));
        this.accountId = accountId;
        this.requestedAmount = requestedAmount;
        this.availableBalance = availableBalance;
        this.overdraftLimit = overdraftLimit;
    }

    /**
     * @return the account the withdrawal was attempted against
     */
    public UUID accountId() {
        return accountId;
    }

    /**
     * @return the amount that was requested
     */
    public Money requestedAmount() {
        return requestedAmount;
    }

    /**
     * @return the available balance before the withdrawal
     */
    public Money availableBalance() {
        return availableBalance;
    }

    /**
     * @return the agreed overdraft allowance
     */
    public Money overdraftLimit() {
        return overdraftLimit;
    }

    @Override
    public String code() {
        return CODE;
    }
}
