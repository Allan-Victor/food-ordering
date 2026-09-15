package com.allan.food.order.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * JPA-side counterpart of the domain's {@code Money} value object.
 *
 * <p><b>This class is the pure-hexagonal tax, made visible.</b> Domain {@code Money} is an immutable record
 * with a validating constructor, currency-aware scaling and {@code HALF_EVEN} rounding. JPA cannot use it: the
 * specification requires embeddables to have a no-arg constructor and mutable state so the provider can
 * instantiate and populate them reflectively. Those two requirements are the exact opposite of what makes a
 * good value object. Rather than compromise the domain to satisfy the framework, we keep a second, deliberately
 * dumb representation here. It has no validation and no behaviour — it is a row shape, nothing more.
 *
 * <p><b>Alternative you'll see elsewhere:</b> annotate the domain record directly (Hibernate 6 has partial
 * record-embeddable support) or add a protected no-arg constructor and drop {@code final} on the domain type.
 * Both work and both are what most shops ship — and both mean a framework's instantiation strategy dictates
 * your domain's shape. Step 9 of the backlog rebuilds exactly that version side by side so the trade is
 * concrete rather than theoretical.
 *
 * <p><b>On {@code scale = 4}:</b> ISO 4217's largest default fraction digit count is 4 (CLF, UYW), so this
 * accommodates every currency without truncation. The domain rescales to the currency's own precision on
 * reconstitution, so a UGX amount stored as {@code 8000.0000} returns as {@code 8000}. Storing at a fixed
 * scale of 2 — the reference's approach — silently corrupts zero-decimal and three-decimal currencies.
 *
 * <p><b>On denormalised currency:</b> the code repeats on every money column rather than living once per
 * order. That is faithful to the model — each {@code Money} carries its own currency — and keeps the mapper a
 * straight field copy. Normalising it would save bytes and cost clarity.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
class MoneyEmbeddable {

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** ISO 4217 alphabetic code. Stored as text, not an ordinal — see {@code OrderJpaEntity.orderStatus}. */
    @Column(nullable = false, length = 3)
    private String currency;
}
