package com.teya.tinyledger.api.mapper;

import com.teya.tinyledger.api.dto.OpenAccountRequest;
import com.teya.tinyledger.api.dto.RecordTransactionRequest;
import com.teya.tinyledger.domain.Money;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * Translates incoming API payloads into domain values.
 *
 * <p>This is where loosely typed wire data (a currency string, a bare decimal) becomes a
 * strongly typed {@link Money}. Doing it at the edge means nothing behind the HTTP layer ever
 * has to defend against an unparsable currency code.</p>
 */
@ApplicationScoped
public class LedgerRequestMapper {

    /**
     * Resolves the currency an account should be held in.
     *
     * @param request the open-account payload
     * @return the resolved currency
     * @throws IllegalArgumentException if the code is not a known ISO 4217 currency
     */
    public Currency toCurrency(OpenAccountRequest request) {
        return Money.currencyOf(request.currency());
    }

    /**
     * Resolves the agreed overdraft allowance, defaulting to zero when none was supplied.
     *
     * @param request  the open-account payload
     * @param currency the account currency
     * @return the overdraft allowance
     * @throws IllegalArgumentException if the amount is negative or too precise for the currency
     */
    public Money toOverdraftLimit(OpenAccountRequest request, Currency currency) {
        BigDecimal limit = request.overdraftLimit();
        return limit == null ? Money.zero(currency) : Money.of(limit, currency);
    }

    /**
     * Resolves the amount of a money movement.
     *
     * @param request the movement payload
     * @return the amount, in the currency supplied by the caller
     * @throws IllegalArgumentException if the currency code is unknown or the amount is too
     *                                  precise for that currency
     */
    public Money toAmount(RecordTransactionRequest request) {
        return Money.of(request.amount(), Money.currencyOf(request.currency()));
    }
}
