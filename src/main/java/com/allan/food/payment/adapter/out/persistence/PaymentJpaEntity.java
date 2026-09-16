package com.allan.food.payment.adapter.out.persistence;

import com.allan.food.payment.domain.model.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Row shape for a payment record.
 *
 * <p><b>The unique constraint on {@code order_id} is the idempotency guarantee, not a data-quality nicety.</b>
 * {@code PaymentService} looks up by order id before charging, but a lookup is a check-then-act: two duplicate
 * commands processed concurrently both find nothing and both proceed. Only the constraint actually closes that
 * race — the loser's insert fails, its transaction rolls back, the message is redelivered, and the retry finds
 * the committed record and replays the reply. <b>Application logic makes the common case fast; the constraint
 * makes it correct.</b> Remove this line and the participant becomes idempotent only under low concurrency,
 * which is the worst kind of bug: invisible in testing, occasional in production.
 *
 * <p><b>No foreign key to {@code orders}, despite holding an order id.</b> The two tables share a database in
 * Slice 2 and a constraint would be trivially addable — which is exactly why it must be consciously refused. A
 * FK across a context boundary is the change that turns Slice 4's split from a deployment exercise into a data
 * migration. The order id here is a reference to another context's identifier, carried the way it would be
 * carried over a network: as an opaque value this context does not enforce.
 *
 * <p>Everything else follows the pattern established by {@code OrderJpaEntity}: domain-assigned {@code UUID}
 * id, {@code Long} (never primitive) {@code @Version} so Spring Data's newness detection skips the pre-insert
 * {@code SELECT}, {@code EnumType.STRING} so reordering the enum cannot silently reinterpret stored rows.
 *
 * <p>{@code Instant} maps natively — Hibernate 6 handles {@code java.time} types without a converter, unlike
 * the {@code @Convert} boilerplate older codebases carry.
 */
@Entity
@Table(
        name = "payments",
        uniqueConstraints = @UniqueConstraint(name = "uk_payments_order_id", columnNames = "order_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
class PaymentJpaEntity {

    @Id
    @EqualsAndHashCode.Include
    private UUID id;

    @Version
    private Long version;

/** Another context's identifier. Indexed and unique, but deliberately not a foreign key. */
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private UUID customerId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "amount", nullable = false, precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "currency", nullable = false, length = 3))
    })
    private MoneyEmbeddable amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(nullable = false)
    private Instant createdAt;
}
