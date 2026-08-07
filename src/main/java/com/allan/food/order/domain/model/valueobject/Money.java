package com.allan.food.order.domain.model.valueobject;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A monetary amount in a single currency.
 *
 * <p><strong>Value object.</strong> Two {@code Money} instances with the same
 * amount and currency are interchangeable — there is no notion of "which"
 * five thousand shillings — so this type has no identity and equality is by
 * value. Being a {@code record} gives that equality, immutability, and the
 * accessors for free.
 *
 * <p><strong>Valid by construction.</strong> The compact constructor rejects
 * null, blank currency, and negative amounts, and normalises scale to two
 * decimal places. Because every {@code Money} that exists is already
 * normalised, the record's generated {@code equals} is reliable: {@code 10.5}
 * and {@code 10.50} compare equal, which a raw {@link BigDecimal#equals}
 * would not, since it compares scale.
 *
 * <p><strong>Why {@link BigDecimal} and never {@code double}.</strong> Binary
 * floating point cannot represent most decimal fractions exactly
 * ({@code 0.1 + 0.2 != 0.3}). For money that is not a rounding nuisance but an
 * accounting defect, so the amount is a {@code BigDecimal} throughout and is
 * only ever built from a {@code String} or another {@code BigDecimal}, never
 * from a {@code double}.
 *
 * <p><strong>Note on the reference implementation.</strong> A common teaching
 * version of this class omits currency, which quietly assumes a single-currency
 * system and cannot detect adding one currency to another. Currency is kept
 * here so the invariant is enforceable.
 */

public record Money(BigDecimal amount, String currency) {
    public Money {
        if (amount == null) {
            throw new IllegalArgumentException("amount cannot be null");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency cannot be null or blank");
        }
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("amount cannot be negative: " + amount);
        }
        // HALF_EVEN ("banker's rounding") avoids the upward bias of HALF_UP
        // accumulated over many operations, and is the IEEE 754 / financial default.
        amount = amount.setScale(2, RoundingMode.HALF_EVEN);
    }

    /** Preferred factory: the {@code String} form is exact, unlike {@code new BigDecimal(double)}. */
    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    // ----- operations -----

    public boolean isGreaterThanZero() {
        return amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    /**
     * Scales the amount by a whole quantity.
     *
     * <p>The multiplier is an {@code int}, not a {@code Money}: money times a
     * scalar is meaningful (a line total), money times money is not (there is
     * no such thing as a squared currency). The type signature encodes that rule.
     */
    public Money multiply(int multiplier) {
        return new Money(amount.multiply(BigDecimal.valueOf(multiplier)), currency);
    }

    /**
     * Guards every operation that combines two amounts. Adding UGX to USD is a
     * defect, not a rounding question, so it fails loudly at the point of the
     * mistake rather than producing a meaningless number that flows downstream.
     */
    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: %s vs %s".formatted(currency, other.currency));
        }
    }
}
