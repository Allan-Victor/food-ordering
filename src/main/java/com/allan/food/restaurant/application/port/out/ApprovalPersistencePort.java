package com.allan.food.restaurant.application.port.out;

import com.allan.food.restaurant.domain.model.OrderApproval;
import com.allan.food.restaurant.domain.model.RestaurantAvailability;

import java.util.Optional;
import java.util.UUID;

/**
 * Driven port for this context's persistence.
 *
 * <p>One port covering both aggregates, for the reason argued on {@code PaymentPersistencePort}: a single
 * service is the only caller, so splitting would produce interfaces with nobody to serve.
 *
 * <p>{@link #findApprovalByOrderId} is the idempotency lookup — the participant's ability to recognise a
 * repeated command is a persistence question before it is a logic question.
 *
 * <p>{@link #findAvailability} is a read of authoritative context-owned data, not a replica lookup. In Slice 2
 * the adapter serves it from a seeded in-memory source; in a real deployment it would be maintained by the
 * restaurant's own operational tooling. Either way the port is unchanged, which is the point of stating it as
 * one.
 */
public interface ApprovalPersistencePort {

    /** The idempotency lookup: has this order already been decided, either way? */
    Optional<OrderApproval> findApprovalByOrderId(UUID orderId);

    OrderApproval saveApproval(OrderApproval approval);

    Optional<RestaurantAvailability> findAvailability(UUID restaurantId);
}
