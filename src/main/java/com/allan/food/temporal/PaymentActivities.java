package com.allan.food.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * How the payment context receives saga commands on this branch.
 *
 * <p>See {@code OrderSagaWorkflow}'s package Javadoc for why this contract lives in {@code temporal} rather
 * than {@code payment} itself. Its implementation lives in {@code payment.adapter.in.saga} — the same package
 * {@code InProcessPaymentCommandAdapter} occupies on {@code main} — and calls {@code PaymentService} directly;
 * no event publishing, since there's no reply to publish — the return value is the reply.
 *
 * <p>Both outcomes here are ordinary results, never exceptions — payment decline is a business outcome, not a
 * fault. A thrown {@code ApplicationFailure} from this interface means the payment gateway itself is
 * unreachable or timed out, a technical fault Temporal's retry policy should handle; it never means
 * "declined."
 */
@ActivityInterface
public interface PaymentActivities {

    @ActivityMethod
    PaymentResult processPayment(ProcessPaymentRequest request);

    @ActivityMethod
    void refundPayment(RefundPaymentRequest request);

    record ProcessPaymentRequest(UUID orderId, UUID customerId, BigDecimal amount, String currency) {}

    record RefundPaymentRequest(UUID orderId, UUID customerId, BigDecimal amount, String currency) {}

    /**
     * Mirrors the sealed-domain-event convention already locked for {@code DomainEvent}: an exhaustive,
     * compiler-checked pair of outcomes rather than a boolean-plus-reasons flag that callers can inspect
     * incorrectly.
     */
    sealed interface PaymentResult {
        record Completed() implements PaymentResult {}
        record Declined(List<String> reasons) implements PaymentResult {}
    }
}
