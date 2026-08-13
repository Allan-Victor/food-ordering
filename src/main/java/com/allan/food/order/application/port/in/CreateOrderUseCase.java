package com.allan.food.order.application.port.in;

import com.allan.food.order.application.port.in.command.CreateOrderCommand;
import com.allan.food.order.application.port.in.command.CreateOrderResult;
import jakarta.validation.Valid;

/**
 * Driving port for placing an order - the application's inbound contract for the
 * {@code POST /orders} use case.
 *
 * <p><b>Design decision:</b> We segregate one interface <i>per use case</i> rather than exposing a
 * single {@code OrderApplicationService} bundling create, track, and later the saga callbacks. A
 * driving adapter that only creates orders depends on exactly this method - interface segregation
 * applied to ports, split among the command/query line ({@link TrackOrderUseCase} is the read side).
 *
 * <p><b>Alternative you'll see elsewhere:</b> use a fat {@code OrderApplicationService} with create
 * and track side by side - fewer files, but every adapter transitively depends on methods it never
 * calls and the read/write split blurs.
 *
 * <p>The {@code @Valid} on the parameter makes this port <i>self-protecting</i>: {@link CreateOrderCommand}
 * validation fires for any driving adapter, not just the web layer. Enforcement is wired when the
 * implementing service is marked {@code @Validated}
 */
public interface CreateOrderUseCase {
    /**
     * Validates and places a new order.
     *
     * @param command customer, restaurant, delivery address, and requested lines (product + quantity
     *                only - the server derives all money)
     * @return the {@link CreateOrderResult} carrying the tracking id the caller polls with
     */
    CreateOrderResult createOrder(@Valid CreateOrderCommand command);
}
