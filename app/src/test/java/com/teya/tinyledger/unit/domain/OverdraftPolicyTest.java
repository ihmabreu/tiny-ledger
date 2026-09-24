package com.teya.tinyledger.unit.domain;

import com.teya.tinyledger.domain.FixedOverdraftLimitPolicy;
import com.teya.tinyledger.domain.Money;
import com.teya.tinyledger.domain.OverdraftPolicy;
import com.teya.tinyledger.domain.TransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for the overdraft abstraction and the transaction direction enum.
 *
 * <p>{@link OverdraftPolicy} exists so that a different lending rule can be introduced without
 * touching the account's recording logic; these tests describe the one implementation shipped
 * today and the boundary it enforces.</p>
 */
@Tag("unit")
@DisplayName("Overdraft policy and transaction direction")
class OverdraftPolicyTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @ParameterizedTest(name = "a resulting balance of {1} is allowed under a {0} limit: {2}")
    @CsvSource({
            "0.00,   0.00,    true",
            "0.00,   -0.01,   false",
            "0.00,   10.00,   true",
            "100.00, -100.00, true",
            "100.00, -100.01, false",
            "100.00, -99.99,  true"
    })
    @DisplayName("allows a balance down to, but never past, the agreed limit")
    void allows_resultingBalanceAgainstLimit_returnsExpectedValidity(String limit, String resultingBalance, boolean allowed) {
        OverdraftPolicy policy = new FixedOverdraftLimitPolicy(Money.of(limit, EUR));

        assertThat(policy.allows(Money.of(resultingBalance, EUR))).isEqualTo(allowed);
    }

    @Test
    @DisplayName("a policy of none permits no negative balance at all")
    void none_zeroLimitPolicy_disallowsNegativeBalances() {
        OverdraftPolicy policy = FixedOverdraftLimitPolicy.none(EUR);

        assertThat(policy.limit()).isEqualTo(Money.zero(EUR));
        assertThat(policy.allows(Money.zero(EUR))).isTrue();
        assertThat(policy.allows(Money.of("-0.01", EUR))).isFalse();
    }

    @Test
    @DisplayName("rejects a negative limit, which would be a hold rather than an overdraft")
    void constructor_negativeOverdraftLimit_throwsIllegalArgumentException() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FixedOverdraftLimitPolicy(Money.of("-1.00", EUR)));
    }

    @Test
    @DisplayName("a deposit signs positive and a withdrawal signs negative")
    void signed_depositAndWithdrawal_appliesExpectedSign() {
        Money tenEuros = Money.of("10.00", EUR);

        assertThat(TransactionType.DEPOSIT.signed(tenEuros)).isEqualTo(tenEuros);
        assertThat(TransactionType.WITHDRAWAL.signed(tenEuros)).isEqualTo(Money.of("-10.00", EUR));
    }
}
