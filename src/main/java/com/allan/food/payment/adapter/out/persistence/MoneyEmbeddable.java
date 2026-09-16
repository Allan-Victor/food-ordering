package com.allan.food.payment.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;


/**
 * JPA-side counterpart of this context's {@code Money}.
 *
 * <p><b>The third copy of this class in the codebase</b> — one per context that handles money, plus the domain
 * records themselves. Worth noticing rather than glossing: strict hexagonal costs a mapping type per value
 * object, and bounded contexts cost a copy per context. Neither is waste, but the arithmetic multiplies, and
 * this is what people mean when they say the pure style is expensive.
 *
 * <p>Scale 4 accommodates every ISO 4217 currency; the domain rescales to the currency's own fraction digits
 * on reconstitution. See the order context's equivalent for the full argument.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
class MoneyEmbeddable {

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;
}
