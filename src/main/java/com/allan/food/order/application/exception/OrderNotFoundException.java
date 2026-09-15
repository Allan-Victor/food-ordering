package com.allan.food.order.application.exception;

import java.io.Serial;
import java.util.UUID;

/**
 * Signals that no order exists for the given customer-facing tracking id.
 *
 * <p><b>Decision:</b> kept as a separate type from {@link RestaurantNotFoundException} rather than sharing a
 * common {@code NotFoundException} base. They carry different identifiers and map to different HTTP statuses
 * (404 here — the addressed resource genuinely does not exist), so a shared supertype would only exist to be
 * immediately re-narrowed by the handler.
 *
 * <p><b>Alternative you'll see elsewhere:</b> a single generic {@code ObjectNotFoundException(objectName, id)}
 * (the hogwarts-artifacts-online approach). Fewer classes, but the type no longer tells the boundary what
 * status to return, so the advice has to branch on a string.
 */
public class OrderNotFoundException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID trackingId;

    public OrderNotFoundException(UUID trackingId) {
        super("No order found for tracking id " + trackingId);
        this.trackingId = trackingId;
    }

    public UUID getTrackingId() {
        return trackingId;
    }
}
