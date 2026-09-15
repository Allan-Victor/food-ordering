package com.allan.food.order.domain.model.valueobject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

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
 * null, blank currency, unknown currency codes, and negative amounts, then normalises the scale.
 * Because every {@code Money} that exists is already normalised, the record's
 * generated {@code equals} is reliable: {@code 10.5}
 * and {@code 10.50} compare equal, which a raw {@link BigDecimal#equals}
 * would not, since it compares scale as well as value.
 *
 * <p><strong>Scale comes from the currency, not a constant.</strong> A fixed
 * scale of 2 is wrong for a large share of the world's currencies. UGX, JPY, and
 * KRW have no minor unit and take scale 0; KWD and BHD take 3.
 * {@link Currency#getDefaultFractionDigits()} carries the correct value per ISO
 * 4217, so the amount is normalised to whatever that currency actually uses.
 * Constructing the {@link java.util.Currency} also validates the code, an unknown one
 * throws rather than propagating silently.
 *
 * <p><strong>Why {@link BigDecimal} and never {@code double}.</strong> Binary
 * floating point cannot represent most decimal fractions exactly
 * ({@code 0.1 + 0.2 != 0.3}). For money that is not a rounding nuisance but an
 * accounting defect, so the amount is a {@code BigDecimal} throughout and is
 * only ever built from a {@code String} or another {@code BigDecimal}, never
 * from a {@code double}.
 *
 <p><strong>Thread safety.</strong> Immutable, therefore safe to share freely
 * across threads with no synchronisation. This is how a domain model should
 * achieve thread safety — through immutability, never through locks.
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
        /*
         Throws IllegalArgumentExecution on an unknown ISO 4217 code, so this
         doubles as currency-code validation
         */
        int fractionDigits = Currency.getInstance(currency).getDefaultFractionDigits();

        // HALF_EVEN ("banker's rounding") avoids the upward bias of HALF_UP
        // accumulated over many operations, and is the IEEE 754 / financial default.
        amount = amount.setScale(fractionDigits, RoundingMode.HALF_EVEN);
    }

    /** Preferred factory: the {@code String} form is exact, unlike {@code new BigDecimal(double)}. */
    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
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
