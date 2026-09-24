package com.teya.tinyledger.unit.domain;

import com.teya.tinyledger.domain.Money;
import com.teya.tinyledger.domain.exception.CurrencyMismatchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for the {@link Money} value object.
 *
 * <p>Money is the foundation every other correctness guarantee rests on, so its rounding,
 * scaling and currency rules are pinned down exhaustively here rather than being inferred from
 * higher-level tests.</p>
 */
@Tag("unit")
@DisplayName("Money")
class MoneyTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency GBP = Currency.getInstance("GBP");
    private static final Currency JPY = Currency.getInstance("JPY");

    @Nested
    @DisplayName("scaling to the currency's minor unit")
    class Scaling {

        @ParameterizedTest(name = "{0} {1} is normalised to {2}")
        @CsvSource({
                "10,      EUR, 10.00",
                "10.5,    EUR, 10.50",
                "10.50,   EUR, 10.50",
                "-3,      EUR, -3.00",
                "0,       EUR, 0.00",
                "1000,    JPY, 1000",
                "1000.00, JPY, 1000"
        })
        @DisplayName("pads or trims the scale without changing the value")
        void of_variousStringAmountsAndCurrencies_normalisesScaleToMinorUnits(String input, String currencyCode, String expected) {
            Money money = Money.of(input, currencyCode);

            assertThat(money.toPlainString()).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} EUR is rejected")
        @ValueSource(strings = {"10.001", "0.005", "-1.239"})
        @DisplayName("rejects amounts more precise than the currency allows")
        void of_amountMorePreciseThanMinorUnits_throwsIllegalArgumentException(String input) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Money.of(input, EUR))
                    .withMessageContaining("EUR");
        }

        @Test
        @DisplayName("rejects fractional amounts for a currency with no minor unit")
        void of_fractionalAmountForZeroDecimalCurrency_throwsIllegalArgumentException() {
            assertThatIllegalArgumentException().isThrownBy(() -> Money.of("100.5", JPY));
        }

        @Test
        @DisplayName("treats trailing zeros as insignificant")
        void of_amountWithTrailingZeros_normalisesScale() {
            assertThat(Money.of("10.5000", EUR).toPlainString()).isEqualTo("10.50");
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        @DisplayName("adds amounts of the same currency")
        void add_sameCurrency_returnsSum() {
            assertThat(Money.of("10.25", EUR).add(Money.of("0.75", EUR)))
                    .isEqualTo(Money.of("11.00", EUR));
        }

        @Test
        @DisplayName("subtracts amounts of the same currency, allowing a negative result")
        void subtract_sameCurrencyExceedingMinuend_returnsNegativeResult() {
            assertThat(Money.of("10.00", EUR).subtract(Money.of("25.00", EUR)))
                    .isEqualTo(Money.of("-15.00", EUR));
        }

        @Test
        @DisplayName("negates an amount")
        void negate_positiveAmount_returnsNegativeAmount() {
            assertThat(Money.of("10.00", EUR).negate()).isEqualTo(Money.of("-10.00", EUR));
        }

        @Test
        @DisplayName("refuses to mix currencies when adding")
        void add_differentCurrencies_throwsCurrencyMismatchException() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> Money.of("10.00", EUR).add(Money.of("10.00", GBP)));
        }

        @Test
        @DisplayName("refuses to mix currencies when subtracting")
        void subtract_differentCurrencies_throwsCurrencyMismatchException() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> Money.of("10.00", EUR).subtract(Money.of("10.00", GBP)));
        }

        @Test
        @DisplayName("keeps precision that floating point arithmetic would lose")
        void add_multipleRepeatedAdditions_preservesExactDecimalPrecision() {
            Money total = Money.zero(EUR);
            for (int i = 0; i < 10; i++) {
                total = total.add(Money.of("0.10", EUR));
            }

            assertThat(total).isEqualTo(Money.of("1.00", EUR));
        }
    }

    @Nested
    @DisplayName("sign predicates")
    class Signs {

        @Test
        @DisplayName("classifies positive, negative and zero amounts")
        void isPositiveAndIsNegativeAndIsZero_variousAmounts_classifiesCorrectly() {
            assertThat(Money.of("0.01", EUR).isPositive()).isTrue();
            assertThat(Money.of("0.01", EUR).isNegative()).isFalse();
            assertThat(Money.of("-0.01", EUR).isNegative()).isTrue();
            assertThat(Money.zero(EUR).isZero()).isTrue();
            assertThat(Money.zero(EUR).isPositive()).isFalse();
            assertThat(Money.zero(EUR).isNegative()).isFalse();
        }

        @Test
        @DisplayName("treats 0.00 and 0 as the same zero")
        void equals_zeroWithDifferentScale_returnsTrue() {
            assertThat(Money.of("0.00", EUR)).isEqualTo(Money.of("0", EUR));
        }
    }

    @Nested
    @DisplayName("comparison")
    class Comparison {

        @Test
        @DisplayName("orders amounts of the same currency")
        void compareTo_sameCurrency_ordersCorrectly() {
            assertThat(Money.of("5.00", EUR)).isLessThan(Money.of("5.01", EUR));
            assertThat(Money.of("-5.00", EUR)).isLessThan(Money.zero(EUR));
        }

        @Test
        @DisplayName("refuses to order amounts of different currencies")
        void compareTo_differentCurrencies_throwsCurrencyMismatchException() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> Money.of("5.00", EUR).compareTo(Money.of("5.00", GBP)));
        }
    }

    @Nested
    @DisplayName("currency resolution")
    class CurrencyResolution {

        @Test
        @DisplayName("resolves a valid ISO 4217 code, ignoring case and padding")
        void currencyOf_validIsoCodeWithWhitespaceAndMixedCase_resolvesCurrency() {
            assertThat(Money.currencyOf(" eur ")).isEqualTo(EUR);
        }

        @ParameterizedTest(name = "\"{0}\" is rejected")
        @ValueSource(strings = {"EURO", "E", "123", "  "})
        @DisplayName("rejects codes that are not ISO 4217")
        void currencyOf_invalidIsoCode_throwsIllegalArgumentException(String code) {
            assertThatIllegalArgumentException().isThrownBy(() -> Money.currencyOf(code));
        }

        @Test
        @DisplayName("rejects a null code")
        void currencyOf_nullCode_throwsIllegalArgumentException() {
            assertThatIllegalArgumentException().isThrownBy(() -> Money.currencyOf(null));
        }
    }

    @Nested
    @DisplayName("construction guards")
    class Construction {

        @Test
        @DisplayName("rejects a null amount")
        void constructor_nullAmount_throwsNullPointerException() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new Money(null, EUR));
        }

        @Test
        @DisplayName("rejects a null currency")
        void constructor_nullCurrency_throwsNullPointerException() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new Money(BigDecimal.ONE, null));
        }

        @Test
        @DisplayName("renders amount and currency together")
        void toString_validMoney_rendersAmountAndCurrencyCode() {
            assertThat(Money.of("10.00", EUR)).hasToString("10.00 EUR");
        }
    }
}
