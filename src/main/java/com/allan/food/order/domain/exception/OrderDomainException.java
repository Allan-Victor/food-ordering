package com.allan.food.order.domain.exception;

/**
 * Raised when a domain rule or invariant of the order model is violated —
 * an illegal state transition, a failed validation, an unknown product.
 *
 * <p>Unchecked, because a domain-rule breach is a programming or input error the
 * caller cannot meaningfully recover from mid-operation; it propagates to the
 * adapter layer, which maps it to a suitable response (for example HTTP 409).
 *
 * <p>Extends a shared {@code DomainException} in the reference so that all
 * contexts' failures can be caught generically. That base is introduced here
 * only when a second context exists, to avoid a speculative shared package.
 */
public class OrderDomainException extends DomainException {

    public OrderDomainException(String message) {
        super(message);
    }

    public OrderDomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
