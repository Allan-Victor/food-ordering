package com.allan.food.restaurant.domain.model;

import com.allan.food.restaurant.domain.exception.RestaurantDomainException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The durable record of one approval decision. Aggregate root.
 *
 * <p><b>Separate from {@link RestaurantAvailability}, unlike the reference</b>, which hangs the approval being
 * decided off its {@code Restaurant} aggregate. Those are different things with different lifetimes: a
 * restaurant's availability is long-lived and changes many times a day, an approval is created once for one
 * order and never changes again. Combining them gives an aggregate with two unrelated reasons to change and no
 * clear consistency boundary — and, practically, makes the idempotency lookup awkward, because you would be
 * loading a restaurant to find out about an order.
 *
 * <p><b>Immutable after creation, with no transition methods at all.</b> {@code Order} has {@code pay},
 * {@code approve}, {@code cancel}; {@code Payment} has {@code markRefunded}. This aggregate has nothing,
 * because a pivot decision cannot be revised. The class being immutable is not a stylistic preference — it is
 * the irreversibility of the pivot enforced by the compiler. If someone later adds a {@code revoke} method,
 * they have not extended this class; they have reclassified the saga step, and the orchestrator's compensation
 * design no longer holds.
 *
 * <p><b>This is the participant's idempotency key</b>, exactly as {@code Payment} is for the payment context.
 * Keyed uniquely by {@code orderId}, enforced by a database constraint as well as by lookup, since a
 * check-then-act under concurrent duplicates is a race that only the constraint closes.
 *
 * <p>Rejections are persisted alongside approvals, for the reason argued on {@code Payment}: a duplicate
 * command for a previously-rejected order must reply {@code OrderRejected} again rather than re-deciding.
 * Re-deciding would be worse here than in payment, because availability is volatile — a second evaluation of
 * the same order could legitimately reach the opposite conclusion, and the saga would receive two contradictory
 * replies for one command.
 */
public final class OrderApproval {

    public static final long NEW_VERSION = -1L;

    private final UUID approvalId;
    private final UUID orderId;
    private final UUID restaurantId;
    private final ApprovalStatus status;
    private final List<String> reasons;
    private final Instant decidedAt;
    private final long version;

    private OrderApproval(UUID approvalId, UUID orderId, UUID restaurantId, ApprovalStatus status,
                          List<String> reasons, Instant decidedAt, long version) {

        if (approvalId == null) throw new RestaurantDomainException("approvalId is required");
        if (orderId == null) throw new RestaurantDomainException("orderId is required");
        if (restaurantId == null) throw new RestaurantDomainException("restaurantId is required");
        if (status == null) throw new RestaurantDomainException("Status is required");
        if (decidedAt == null) throw new RestaurantDomainException("decidedAt is required");

        List<String> copied = reasons == null ? List.of() : List.copyOf(reasons);
        if (status == ApprovalStatus.REJECTED && copied.isEmpty()) {
            throw new RestaurantDomainException("a rejection must record at least one reason");
        }

        this.approvalId = approvalId;
        this.orderId = orderId;
        this.restaurantId = restaurantId;
        this.status = status;
        this.reasons = reasons;
        this.decidedAt = decidedAt;
        this.version = version;
    }

    /**
     * Records acceptance. The pivot commits here.
     *
     * <p>Takes no reasons: an approval needs no justification, and a parameter that is always empty is a
     * parameter that will eventually be misused.
     */
    public static OrderApproval approved(UUID orderId, UUID restaurantId, Instant at) {
        return new OrderApproval(UUID.randomUUID(), orderId, restaurantId,
                ApprovalStatus.APPROVED, List.of(), at, NEW_VERSION);
    }

    /**
     * Records refusal, with the reasons that will reach the customer.
     *
     * <p>The reasons are required — enforced in the constructor — because they travel through
     * {@code OrderRejected} into the order's failure messages and out to the tracking endpoint. A rejection
     * that cannot explain itself is a support ticket.
     */
    public static OrderApproval rejected(UUID orderId, UUID restaurantId, List<String> reasons, Instant at) {
        return new OrderApproval(UUID.randomUUID(), orderId, restaurantId,
                ApprovalStatus.REJECTED, reasons, at, NEW_VERSION);
    }
    /** Rebuilds from storage, running no rules. For the persistence mapper. */
    public static OrderApproval reconstitute(UUID approvalId, UUID orderId, UUID restaurantId,
                                             ApprovalStatus status, List<String> reasons,
                                             Instant decidedAt, long version) {
        return new OrderApproval(approvalId, orderId, restaurantId, status, reasons, decidedAt, version);
    }

    public UUID approvalId()      { return approvalId; }
    public UUID orderId()         { return orderId; }
    public UUID restaurantId()    { return restaurantId; }
    public ApprovalStatus status(){ return status; }
    public Instant decidedAt()    { return decidedAt; }
    public long version()         { return version; }

    public List<String> reasons() {
        return reasons;   // already an unmodifiable copy
    }

    public boolean isNew() {
        return version == NEW_VERSION;
    }
}
