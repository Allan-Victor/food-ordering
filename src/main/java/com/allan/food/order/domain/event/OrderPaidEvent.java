package com.allan.food.order.domain.event;

import com.allan.food.order.domain.model.entity.Order;
import com.allan.food.order.domain.model.valueobject.Money;

import java.time.Instant;
import java.util.UUID;

/** Raised when payment for an order has been captured. */
public record OrderPaidEvent(
        UUID orderId,
        UUID restaurantId,
        Money price,
        Instant occurredAt) implements DomainEvent {

    public static OrderPaidEvent from(Order order) {
        return new OrderPaidEvent(
                order.orderId(),
                order.restaurantId(),
                order.price(),
                Instant.now()
        );
    }

}
