package com.allan.food.order.domain.model.valueobject;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Value Object: an amount in a currency.
 * <p>
 * Immutable (record), equality by value, and
 * impossible to construct in an invalid state - the compact
 * constructor normalises scale and validates once, so every Money
 * that exists is already clean. That is what lets equals() be
 * reliable and removes the need for null-guards in the methods.
 * <p>
 * @param amount
 * @param currency
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
        amount = amount.setScale(2, RoundingMode.HALF_EVEN);
    }

    // ----- factories -----
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

    public Money multiply(int multiplier) {
        return new Money(amount.multiply(BigDecimal.valueOf(multiplier)), currency);
    }

    // ----- invariant guard -----

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: %s vs %s".formatted(currency, other.currency));
        }
    }
}
