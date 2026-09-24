package com.teya.tinyledger.domain;

/**
 * Decides how far an account is allowed to go into negative territory.
 *
 * <p>Modelled as an abstraction rather than a bare {@code BigDecimal} field so that a
 * different rule &mdash; a tiered allowance, a time-limited buffer, a policy that refuses any
 * overdraft on a savings product &mdash; can be introduced by adding an implementation, without
 * editing the account or the service that consumes it.</p>
 */
public interface OverdraftPolicy {

    /**
     * The agreed overdraft allowance.
     *
     * <p>This is account metadata describing borrowing capacity. It is never recorded as a
     * transaction and never forms part of the available balance.</p>
     *
     * @return the non-negative allowance
     */
    Money limit();

    /**
     * Decides whether an account may end up at the supplied available balance.
     *
     * @param resultingAvailableBalance the available balance a movement would leave behind
     * @return {@code true} if the resulting balance is permitted
     */
    boolean allows(Money resultingAvailableBalance);
}
