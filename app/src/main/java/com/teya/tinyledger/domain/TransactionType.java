package com.teya.tinyledger.domain;

/**
 * The direction of a money movement.
 *
 * <p>Each constant knows how to turn a positive amount into the signed amount that should be
 * folded into a balance. Keeping that rule inside the enum means new movement types can be
 * introduced without touching the balance arithmetic that consumes them, which keeps the
 * balance calculation closed for modification but open for extension.</p>
 */
public enum TransactionType {

    /** Money paid into the account, increasing the balance. */
    DEPOSIT {
        @Override
        public Money signed(Money amount) {
            return amount;
        }
    },

    /** Money taken out of the account, decreasing the balance. */
    WITHDRAWAL {
        @Override
        public Money signed(Money amount) {
            return amount.negate();
        }
    };

    /**
     * Applies this movement's direction to a positive amount.
     *
     * @param amount the positive amount that was moved
     * @return the signed amount to fold into a balance
     */
    public abstract Money signed(Money amount);
}
