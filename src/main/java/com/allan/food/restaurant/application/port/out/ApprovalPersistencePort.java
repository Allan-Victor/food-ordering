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
 */
public interface ApprovalPersistencePort {

    /** The idempotency lookup: has this order already been decided, either way? */
    Optional<OrderApproval> findApprovalByOrderId(UUID orderId);

    OrderApproval saveApproval(OrderApproval approval);

}
