package com.allan.food.order.domain.event;

import com.allan.food.order.domain.model.entity.Order;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Raised when an Order's payment must be rolled back.
 *
 * <p>Note this corresponds to {@code initCancel}, not {@code cancel} - it is
 * the <em>start</em> of the compensation, published so that the payment side
 * can refund. The terminal {@code cancel} raises no event because nothing
 * downstream needs to react to it.
 */
public record OrderCancelledEvent(
        UUID orderId,
        UUID customerId,
        List<String> failureMessages,
        Instant occurredAt) implements DomainEvent {

    /** Defensive copy: an event is immutable, so its collections must be too.*/
    public OrderCancelledEvent{
        failureMessages =  failureMessages == null ? List.of() : List.copyOf(failureMessages);
    }

    public static OrderCancelledEvent from(Order order) {
        return new OrderCancelledEvent(
                order.orderId(),
                order.customerId(),
                order.failureMessages(),
                Instant.now());
    }
}
