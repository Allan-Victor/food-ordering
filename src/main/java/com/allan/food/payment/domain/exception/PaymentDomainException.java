package com.allan.food.payment.domain.exception;

/**
 * A payment domain rule was violated.
 *
 * <p><b>Its own type, not shared with {@code OrderDomainException}.</b> The reference hoists a common
 * {@code DomainException} into a shared module inherited by every context. That looks like sensible reuse and
 * is the beginning of a shared kernel: the moment one context needs a field on it, every other context
 * redeploys. Two nearly-identical exception classes is the cheaper problem.
 */
public class PaymentDomainException extends RuntimeException {
    public PaymentDomainException(String message) {
        super(message);
    }
}
