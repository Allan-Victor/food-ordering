package com.allan.food.payment.adapter.in.saga;

import com.allan.food.payment.application.port.out.PaymentReplyPort;
import com.allan.food.saga.contract.SagaContract.PaymentCompleted;
import com.allan.food.saga.contract.SagaContract.PaymentFailed;
import com.allan.food.saga.contract.SagaContract.PaymentRefunded;
import com.allan.food.temporal.PaymentActivities.PaymentResult;
import org.springframework.stereotype.Component;

/**
 * Stands in for the event-publishing {@code PaymentReplyPort} adapter {@code main} wires, capturing
 * {@code PaymentService}'s reply on the calling thread instead of publishing it, so
 * {@link PaymentActivitiesImpl} can hand it back as an ordinary return value.
 *
 * <h2>Why {@code PaymentService} needed no changes at all</h2>
 *
 * It depends on the {@code PaymentReplyPort} interface, never on a concrete adapter — the textbook case ports
 * and adapters exists for. Swapping which adapter satisfies the port at wiring time, to match this branch's
 * synchronous transport instead of the event-publishing one, costs the service nothing, because it was already
 * written not to know or care which adapter is behind the port.
 *
 * <h2>Why {@code ThreadLocal} is safe here, not just convenient</h2>
 *
 * Thread-confined by construction, not by discipline: a Temporal activity task runs start-to-finish on one
 * worker thread with nothing else interleaved on it — the same guarantee {@code OrderSaga.withSagaContext}
 * already leans on for its own thread-bound MDC state. {@link #takeProcessResult()} clears what it reads, so a
 * later call on a reused pool thread can never observe a stale result left behind by an earlier one.
 *
 * <p>{@code PaymentService.onRefundPayment} replies on every branch, including its anomalous ones — that
 * guarantee is what lets {@link #takeRefundConfirmation()} clear unconditionally with no defensive null-check.
 */
@Component
class SynchronousPaymentReplyPort implements PaymentReplyPort {

    private final ThreadLocal<PaymentResult> processResult = new ThreadLocal<>();
    private final ThreadLocal<PaymentRefunded> refundResult = new ThreadLocal<>();

    @Override
    public void completed(PaymentCompleted reply) {
        processResult.set(new PaymentResult.Completed());
    }

    @Override
    public void failed(PaymentFailed reply) {
        processResult.set(new PaymentResult.Declined(reply.reasons()));
    }

    @Override
    public void refunded(PaymentRefunded reply) {
        refundResult.set(reply);
    }

    PaymentResult takeProcessResult() {
        PaymentResult result = processResult.get();
        processResult.remove();
        return result;
    }

    void takeRefundConfirmation() {
        refundResult.remove();
    }
}
