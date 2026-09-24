package com.teya.tinyledger.domain;

import com.teya.tinyledger.domain.exception.CurrencyMismatchException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An immutable monetary amount in a specific currency.
 *
 * <p>Money is modelled as a value object rather than a bare {@link BigDecimal} so that the
 * currency always travels with the amount. This makes it impossible to accidentally add
 * euros to dollars: every arithmetic operation validates that both operands share the same
 * currency.</p>
 *
 * <p>Amounts are always normalised to the currency's minor unit (two decimal places for
 * {@code EUR}, zero for {@code JPY}, and so on). Input carrying more significant decimal
 * places than the currency supports is rejected rather than silently rounded, because
 * silently losing a fraction of a cent is never acceptable in a ledger.</p>
 *
 * <p>Binary floating point ({@code double}/{@code float}) is deliberately never used, as it
 * cannot represent common decimal fractions exactly.</p>
 *
 * @param amount   the numeric amount, scaled to the currency's minor unit
 * @param currency the ISO 4217 currency of the amount
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    /**
     * Canonical constructor that validates and normalises the amount.
     *
     * @throws NullPointerException     if {@code amount} or {@code currency} is {@code null}
     * @throws IllegalArgumentException if the amount carries more decimal places than the
     *                                  currency's minor unit supports
     */
    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");

        int fractionDigits = minorUnitDigits(currency);
        if (amount.stripTrailingZeros().scale() > fractionDigits) {
            throw new IllegalArgumentException(
                    "Amount %s has more decimal places than %s supports (%d)"
                            .formatted(amount.toPlainString(), currency.getCurrencyCode(), fractionDigits));
        }
        amount = amount.setScale(fractionDigits, RoundingMode.UNNECESSARY);
    }

    /**
     * Creates a monetary amount.
     *
     * @param amount   the numeric amount
     * @param currency the currency
     * @return the monetary amount, normalised to the currency's minor unit
     */
    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    /**
     * Creates a monetary amount from its decimal string representation.
     *
     * @param amount   the amount, for example {@code "1250.75"}
     * @param currency the currency
     * @return the monetary amount
     * @throws IllegalArgumentException if {@code amount} is not a valid decimal number
     */
    public static Money of(String amount, Currency currency) {
        Objects.requireNonNull(amount, "amount must not be null");
        try {
            return new Money(new BigDecimal(amount.trim()), currency);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'%s' is not a valid decimal amount".formatted(amount), e);
        }
    }

    /**
     * Creates a monetary amount from a decimal string and an ISO 4217 currency code.
     *
     * @param amount       the amount, for example {@code "1250.75"}
     * @param currencyCode the ISO 4217 currency code, for example {@code "EUR"}
     * @return the monetary amount
     * @throws IllegalArgumentException if either the amount or the currency code is invalid
     */
    public static Money of(String amount, String currencyCode) {
        return of(amount, currencyOf(currencyCode));
    }

    /**
     * Returns the zero amount for a currency.
     *
     * @param currency the currency
     * @return zero, scaled to the currency's minor unit
     */
    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    /**
     * Resolves an ISO 4217 currency code into a {@link Currency}.
     *
     * <p>Provided here so that every part of the application reports an unknown currency code
     * in exactly the same way.</p>
     *
     * @param currencyCode the ISO 4217 currency code, for example {@code "EUR"}
     * @return the resolved currency
     * @throws IllegalArgumentException if the code is {@code null}, blank or not a known
     *                                  ISO 4217 currency
     */
    public static Currency currencyOf(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            throw new IllegalArgumentException("Currency code must not be blank");
        }
        try {
            return Currency.getInstance(currencyCode.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'%s' is not a known ISO 4217 currency code".formatted(currencyCode), e);
        }
    }

    /**
     * Adds another amount of the same currency.
     *
     * @param other the amount to add
     * @return a new {@code Money} holding the sum
     * @throws CurrencyMismatchException if the currencies differ
     */
    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    /**
     * Subtracts another amount of the same currency.
     *
     * @param other the amount to subtract
     * @return a new {@code Money} holding the difference
     * @throws CurrencyMismatchException if the currencies differ
     */
    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    /**
     * Returns this amount with the opposite sign.
     *
     * @return the negated amount
     */
    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    /**
     * @return {@code true} if the amount is greater than zero
     */
    public boolean isPositive() {
        return amount.signum() > 0;
    }

    /**
     * @return {@code true} if the amount is less than zero
     */
    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /**
     * @return {@code true} if the amount is exactly zero
     */
    public boolean isZero() {
        return amount.signum() == 0;
    }

    /**
     * @return the amount without exponent notation, for example {@code "1250.75"}
     */
    public String toPlainString() {
        return amount.toPlainString();
    }

    /**
     * Compares two amounts of the same currency by value.
     *
     * @param other the amount to compare against
     * @return a negative number, zero or a positive number as this amount is less than,
     *         equal to, or greater than {@code other}
     * @throws CurrencyMismatchException if the currencies differ
     */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return "%s %s".formatted(amount.toPlainString(), currency.getCurrencyCode());
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    private static int minorUnitDigits(Currency currency) {
        // Pseudo-currencies such as XAU report -1; treat those as whole units.
        return Math.max(currency.getDefaultFractionDigits(), 0);
    }
}
