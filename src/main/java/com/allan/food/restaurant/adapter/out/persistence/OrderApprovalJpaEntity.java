package com.allan.food.restaurant.adapter.out.persistence;

import com.allan.food.restaurant.domain.model.ApprovalStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Row shape for one approval decision.
 *
 * <p><b>Unique on {@code order_id}, for the reason given on {@code PaymentJpaEntity}</b> — and with sharper
 * consequences here. A duplicate that slipped past the lookup would not merely double-charge; it would
 * <i>re-decide</i> against volatile availability and could reach the opposite conclusion, sending the saga two
 * contradictory replies for one command. The constraint is what makes the decision stable, not just singular.
 *
 * <p><b>No {@code @Version}.</b> Every other aggregate in this system has one; this one is immutable after
 * creation, so there is no second write to lose. Adding a version would imply an update path that deliberately
 * does not exist — the pivot's irreversibility showing up one more time, now in the schema. The unique
 * constraint handles the only concurrency this table has, which is competing inserts.
 *
 * <p>Reasons are stored one row per message rather than delimiter-joined, following
 * {@code order_failure_messages}: these strings travel to the customer and a separator inside one would corrupt
 * the rest.
 *
 * <p>No foreign keys to {@code orders} or to any restaurant table — the first because it crosses a context
 * boundary, the second because this context's availability data is not a JPA table at all in Slice 2.
 */
@Entity
@Table(
        name = "order_approvals",
        uniqueConstraints = @UniqueConstraint(name = "uk_order_approvals_order_id", columnNames = "order_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
class OrderApprovalJpaEntity {

    @Id
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private UUID restaurantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApprovalStatus status;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "order_approval_reasons", joinColumns = @JoinColumn(name = "approval_id"))
    @Column(name = "reason", length = 500)
    private List<String> reasons;

    @Column(nullable = false)
    private Instant decidedAt;
}