package com.teya.tinyledger.domain;

import java.util.Currency;
import java.util.Objects;

/**
 * An {@link OverdraftPolicy} that permits the balance to fall to a fixed negative floor.
 *
 * <p>With a limit of {@code 50.00 EUR} the available balance may fall to {@code -50.00 EUR},
 * but no further. A limit of zero forbids any overdraft at all.</p>
 *
 * @param limit the agreed, non-negative overdraft allowance
 */
public record FixedOverdraftLimitPolicy(Money limit) implements OverdraftPolicy {

    /**
     * Canonical constructor validating the allowance.
     *
     * @throws NullPointerException     if the limit is {@code null}
     * @throws IllegalArgumentException if the limit is negative
     */
    public FixedOverdraftLimitPolicy {
        Objects.requireNonNull(limit, "limit must not be null");
        if (limit.isNegative()) {
            throw new IllegalArgumentException("Overdraft limit must not be negative, but was " + limit);
        }
    }

    /**
     * Creates a policy that forbids any overdraft.
     *
     * @param currency the account currency
     * @return a policy with a zero allowance
     */
    public static OverdraftPolicy none(Currency currency) {
        return new FixedOverdraftLimitPolicy(Money.zero(currency));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Permits the balance while {@code resultingAvailableBalance + limit >= 0}, which is the
     * same as saying the account balance (available balance plus allowance) stays non-negative.</p>
     */
    @Override
    public boolean allows(Money resultingAvailableBalance) {
        Objects.requireNonNull(resultingAvailableBalance, "resultingAvailableBalance must not be null");
        return !resultingAvailableBalance.add(limit).isNegative();
    }
}
