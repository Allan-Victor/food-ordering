package com.allan.food.payment.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Row shape for a customer's balance.
 *
 * <p><b>The customer id is the primary key</b>, not a surrogate. One customer has exactly one balance in this
 * context, so a separate identity would be an extra column to keep unique and a second way to say the same
 * thing. The natural key is genuinely natural here, which is rare enough to be worth taking when it happens.
 *
 * <p><b>The version matters more on this table than on any other in the system.</b> Two orders from the same
 * customer paid concurrently both read the same balance, both compute a new one, and without a version the
 * second write silently discards the first — the textbook lost update, with real money. The optimistic lock
 * turns it into a retryable failure.
 *
 * <p>No foreign key to any customer table: this context does not own customers and has no such table. It
 * records a balance against an identifier issued elsewhere, which is the correct posture for a participant.
 */
@Entity
@Table(name = "customer_credits")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
class CustomerCreditJpaEntity {

    @Id
    @EqualsAndHashCode.Include
    private UUID customerId;

    @Version
    private Long version;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "balance_amount", nullable = false, precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "balance_currency", nullable = false, length = 3))
    })
    private MoneyEmbeddable balance;
}
