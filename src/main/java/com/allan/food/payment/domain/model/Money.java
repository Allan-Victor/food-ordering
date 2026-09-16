package com.allan.food.payment.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * A monetary amount in a single currency, as the payment context models it.
 *
 * <p><b>Deliberately duplicated from the order context, and this is the correct call.</b> Importing
 * {@code com.allan.food.order.domain.model.valueobject.Money} would make the order context's domain a shared
 * kernel — the pattern Evans warns requires coordinated change across teams, and the one we already rejected
 * for {@code OrderStatus}. The deciding test is simple: at Slice 4 this context becomes a separate deployable
 * that <i>cannot</i> import Order's classes. Code that must be duplicated then should be duplicated now, or the
 * split turns into a rewrite.
 *
 * <p><b>Not identical, either — and the differences are the point.</b> There is no {@code multiply}, because
 * this context never computes a line total; quantities are the kitchen's concern. There is no
 * {@code isGreaterThanZero}, but there is {@link #isLessThan}, because the only comparison payment cares about
 * is whether a balance covers a charge. Two contexts modelling "money" differently according to what they
 * actually do with it is not duplication going wrong — it is bounded contexts working as designed.
 *
 * <p>Everything structural is shared with the order context's version for the same reasons argued there:
 * {@code BigDecimal} never {@code double}, scale from {@link Currency#getDefaultFractionDigits()} rather than a
 * hardcoded 2, {@code HALF_EVEN} rounding, validation in the compact constructor so the record's generated
 * {@code equals} is trustworthy across input scales.
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
        int fractionDigits = Currency.getInstance(currency).getDefaultFractionDigits();
        amount = amount.setScale(fractionDigits, RoundingMode.HALF_EVEN);
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    /**
     * @throws PaymentDomainException never — a negative result is rejected by the constructor, so callers must
     *         check {@link #isLessThan} first. That is deliberate: an overdrawn balance should be impossible to
     *         represent, not merely impossible to reach.
     */
    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    /** The only comparison this context needs: does a balance cover a charge? */
    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) < 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: %s vs %s".formatted(currency, other.currency));
        }
    }
}
