package com.allan.food.order.application.exception;

import java.io.Serial;
import java.util.UUID;

/**
 * Signals that a {@code CreateOrderCommand} referenced a restaurant this context has no replica for.
 *
 * <p><b>Why this is an application exception, not a domain one:</b> the domain never sees this failure. The
 * domain service receives an already-resolved {@code Restaurant}; the <i>application</i> discovers the absence
 * when it unwraps the {@code Optional} from {@code LoadRestaurantPort}. Placing it here keeps the domain free
 * of lookup concerns.
 *
 * <p><b>Why a distinct type, rather than reusing {@code OrderDomainException}:</b> the reference course throws
 * its domain exception for this case, collapsing two failures with different meanings — and different HTTP
 * mappings — into one type, forcing the web adapter to string-match the message. This maps to 422
 * Unprocessable Content (the endpoint exists; the payload references something that doesn't), whereas a
 * violated domain invariant maps to 409.
 *
 * <p>Unchecked, so {@code @Transactional} rolls back by default and callers aren't forced to handle a failure
 * they cannot recover from.
 */
public class RestaurantNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID restaurantId;

    public RestaurantNotFoundException(UUID restaurantId) {
        super("No restaurant replica found for id " + restaurantId);
        this.restaurantId = restaurantId;
    }

    /** The id that could not be resolved — exposed so the error response can carry it structurally. */
    public UUID getRestaurantId() {
        return restaurantId;
    }
}
