package com.allan.food.order.application.port.in;

import com.allan.food.order.application.port.in.command.TrackOrderQuery;
import com.allan.food.order.application.port.in.command.TrackOrderResponse;
import jakarta.validation.Valid;

/**
 * Driving port for "where is my order?" — the read side, kept separate from {@link CreateOrderUseCase}
 * (command/query separation at the port level).
 *
 * <p><b>Decision:</b> keyed on the <i>tracking id</i> — the opaque, customer-facing handle minted on the
 * aggregate — not the internal order id. "Track my order" is the ubiquitous language; a generic
 * {@code getOrder(id)} would slide the model toward CRUD and leak the persistence identity across the boundary.
 */
public interface TrackOrderUseCase {
    /**
     * @param query the tracking id to look up
     * @return current status and any accumulated failure messages
     */
    TrackOrderResponse trackOrder(@Valid TrackOrderQuery query);
}
