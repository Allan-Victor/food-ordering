package com.allan.food.restaurant.domain.exception;

import java.io.Serial;

/**
 * A restaurant domain rule was violated.
 *
 * <p>Its own type rather than one inherited from a shared module, for the reason argued on
 * {@code PaymentDomainException}: a common exception hierarchy across contexts is a shared kernel that makes
 * every context redeploy when one needs a change.
 */

public class RestaurantDomainException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public RestaurantDomainException(String message) {
        super(message);
    }

    public RestaurantDomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
