package com.teya.tinyledger.domain.exception;

import java.util.Currency;

/**
 * Raised when a movement is expressed in a currency other than the account's own.
 *
 * <p>An account's currency is fixed when it is opened. The ledger performs no foreign
 * exchange, so a mismatching movement is rejected rather than converted.</p>
 */
public class CurrencyMismatchException extends LedgerException {

    /** The machine-readable code reported for this failure. */
    public static final String CODE = "CURRENCY_MISMATCH";

    private final Currency accountCurrency;
    private final Currency requestedCurrency;

    /**
     * Creates the exception.
     *
     * @param accountCurrency   the currency the account is held in
     * @param requestedCurrency the currency that was supplied
     */
    public CurrencyMismatchException(Currency accountCurrency, Currency requestedCurrency) {
        super("Account is held in %s and cannot accept a movement in %s"
                .formatted(accountCurrency.getCurrencyCode(), requestedCurrency.getCurrencyCode()));
        this.accountCurrency = accountCurrency;
        this.requestedCurrency = requestedCurrency;
    }

    /**
     * @return the currency the account is held in
     */
    public Currency accountCurrency() {
        return accountCurrency;
    }

    /**
     * @return the currency that was supplied
     */
    public Currency requestedCurrency() {
        return requestedCurrency;
    }

    @Override
    public String code() {
        return CODE;
    }
}
