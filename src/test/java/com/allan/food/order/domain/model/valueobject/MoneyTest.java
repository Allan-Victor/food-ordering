package com.allan.food.order.domain.model.valueobject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("Money")
class MoneyTest {

    @Nested
    @DisplayName("normalises scale from the currency, not a constant")
    class ScaleNormalisation {

        @ParameterizedTest(name = "{0} {1} -> {2} (scale {3})")
        @CsvSource({
                "UGX, 8000.00, 8000, 0",    // zero minor units
                "JPY, 500.4, 500, 0",
                "USD, 10.5, 10.50, 2",  // two minor units
                "KWD, 1.2345, 1.234, 3" // three minor units; HALF_EVEN drops the 5 to an even digit
        })
        void rescalesToTheCurrencyFractionDigits(String currency, String input, String expected, int expectedScale) {
            Money money = Money.of(input, currency);

            assertThat(money.amount()).isEqualTo(new BigDecimal(expected));
            assertThat(money.amount().scale()).isEqualTo(expectedScale);
        }

        @Test
        @DisplayName("uses banker's rounding, not HALF_UP")
        void roundsHalfToEven() {
            // 2.5 -> 2 (down to even), 3.5 -> 4 (up to even). HALF_UP would give 3 and 4.
            assertThat(Money.of("2.5", "UGX").amount()).isEqualTo(new BigDecimal("2"));
            assertThat(Money.of("3.5", "UGX").amount()).isEqualTo(new BigDecimal("4"));
        }
    }

    @Nested
    @DisplayName("equality")
    class Equality {

        @Test
        @DisplayName("ignores the scale the caller happened to write")
        void equalAcrossInputScales() {
            // Raw BigDecimal.equals is scale-sensitive and would fail this. Normalising in the
            // constructor is what makes the record's generated equals trustworthy for money.
            assertThat(Money.of("10.5", "USD")).isEqualTo(Money.of("10.50", "USD"));
            assertThat(Money.of("10.5", "USD")).hasSameHashCodeAs(Money.of("10.50", "USD"));
        }

        @Test
        void sameAmountInDifferentCurrenciesIsNotEqual() {
            assertThat(Money.of("100", "USD")).isNotEqualTo(Money.of("100", "UGX"));
        }
    }

    @Nested
    @DisplayName("rejects invalid input at construction")
    class ConstructionGuards {

        @Test
        void nullAmount() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Money(null, "UGX"))
                    .withMessageContaining("amount");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "  "})
        void blankCurrency(String currency) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Money.of("100", currency));
        }

        @Test
        void nullCurrency() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Money(BigDecimal.ONE, null));
        }

        @Test
        void negativeAmount() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Money.of("-1", "UGX"))
                    .withMessageContaining("negative");
        }

        @Test
        @DisplayName("an unknown ISO 4217 code, via Currency.getInstance")
        void unknownCurrencyCode() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Money.of("100", "XYZ"));
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        void addsAndSubtractsWithinACurrency() {
            assertThat(Money.of("8000", "UGX")
                    .add(Money.of("2000", "UGX")))
                    .isEqualTo(Money.of("10000", "UGX"));

            assertThat(Money.of("8000", "UGX")
                    .subtract(Money.of("2000", "UGX")))
                    .isEqualTo(Money.of("6000", "UGX"));
        }

        @Test
        void multipliesByAWholeQuantity() {
            assertThat(Money.of("8000", "UGX")
                    .multiply(3))
                    .isEqualTo(Money.of("24000", "UGX"));
        }

        @Test
        @DisplayName("refuses to combine different currencies rather than producing a meaningless number")
        void currencyMismatchFailsLoudly() {
            Money ugx = Money.of("8000", "UGX");
            Money usd = Money.of("10", "USD");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ugx.add(usd))
                    .withMessageContaining("mismatch");

            assertThatIllegalArgumentException()
                    .isThrownBy(()-> ugx.subtract(usd));

            assertThatIllegalArgumentException()
                    .isThrownBy(()-> ugx.isGreaterThan(usd));
        }

        @Test
        void comparisons() {
            assertThat(Money.of("1", "UGX").isGreaterThanZero()).isTrue();
            assertThat(Money.zero("UGX").isGreaterThanZero()).isFalse();
            assertThat(Money.of("2", "UGX").isGreaterThan(Money.of("1", "UGX"))).isTrue();
            assertThat(Money.of("1", "UGX").isGreaterThan(Money.of("1", "UGX"))).isFalse();
        }
    }
}
