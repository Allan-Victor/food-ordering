package com.allan.food.order.domain.event;

import com.allan.food.order.domain.model.entity.Order;
import com.allan.food.order.domain.model.valueobject.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * raised when an order has been successfully created and is waiting payment.
 *
 * <p>Carries  {@code price} because downstream consumers must charge the amount
 * agreed at creation. Recomputing it later would risk charging a figure that
 * differs from the one the customer accepted.
 * @param orderId
 * @param customerId
 * @param restaurantId
 * @param trackingId
 * @param price
 * @param occurredAt
 */
public record OrderCreatedEvent(
        UUID orderId,
        UUID customerId,
        UUID restaurantId,
        UUID trackingId,
        Money price,
        Instant occurredAt
) implements DomainEvent {

    /** Factory taking the aggregate, so call sites cannot mis-order the fields. */
    public static OrderCreatedEvent from(Order order) {
        return new OrderCreatedEvent(
                order.orderId(),
                order.customerId(),
                order.restaurantId(),
                order.trackingId(),
                order.price(),
                Instant.now()
        );
    }
}
