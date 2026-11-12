package com.allan.food.payment.adapter.in.saga;

import com.allan.food.payment.application.port.in.PaymentCommandListener;
import com.allan.food.saga.contract.SagaContract.ProcessPayment;
import com.allan.food.saga.contract.SagaContract.RefundPayment;
import com.allan.food.temporal.PaymentActivities;
import io.temporal.activity.Activity;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * Implements {@link PaymentActivities} by driving the existing {@link PaymentCommandListener} —
 * {@code PaymentService}, unchanged — through {@link SynchronousPaymentReplyPort} in place of the
 * event-publishing adapter {@code main} wires.
 *
 * <h2>Reconstructing the full command</h2>
 *
 * {@code ProcessPaymentRequest} deliberately omits {@code sagaId} and {@code issuedAt} — see
 * {@code PaymentActivities}' Javadoc. {@code PaymentService.onProcessPayment} still requires a full
 * {@link ProcessPayment}, since it is unchanged, so both are synthesised here rather than passed by the
 * workflow — and only because this is activity code. Unlike workflow code, an activity implementation is
 * permitted side effects, so calling {@link Clock#instant()} here is not the determinism violation it would be
 * one layer up.
 *
 * <p>{@code sagaId} specifically comes from {@code Activity.getExecutionContext().getInfo().getWorkflowId()}
 * rather than a freshly minted one, so {@code PaymentService}'s own MDC logging still carries a real
 * correlation id. This only works because the workflow is started with a meaningful {@code WorkflowId} — the
 * order id — rather than a random one; Temporal's uniqueness guarantee on running {@code WorkflowId}s then
 * gives "one saga per order" for free, a stronger guarantee than {@code OrderSaga}'s freshly-minted
 * {@code sagaId} provides on {@code main}.
 *
 * <h2>No {@code @Transactional} here, unlike {@code OrderStateActivitiesImpl}</h2>
 *
 * Not an oversight: {@code PaymentService.onProcessPayment}/{@code onRefundPayment} are already
 * {@code @Transactional} and unchanged. This class calls straight into them, the same as any other caller of an
 * already-transactional service method — adding another annotation here would be redundant, not defensive.
 */
@Component
class PaymentActivitiesImpl implements PaymentActivities {

    private final PaymentCommandListener listener;
    private final SynchronousPaymentReplyPort replyCapture;
    private final Clock clock;

    PaymentActivitiesImpl(PaymentCommandListener listener, SynchronousPaymentReplyPort replyCapture, Clock clock) {
        this.listener = listener;
        this.replyCapture = replyCapture;
        this.clock = clock;
    }

    @Override
    public PaymentResult processPayment(ProcessPaymentRequest request) {
        listener.onProcessPayment(new ProcessPayment(
                correlationId(), request.orderId(), request.customerId(),
                request.amount(), request.currency(), clock.instant()));
        return replyCapture.takeProcessResult();
    }

    @Override
    public void refundPayment(RefundPaymentRequest request) {
        listener.onRefundPayment(new RefundPayment(
                correlationId(), request.orderId(), request.customerId(),
                request.amount(), request.currency(), clock.instant()));
        replyCapture.takeRefundConfirmation();
    }

    private UUID correlationId() {
        return UUID.fromString(Activity.getExecutionContext().getInfo().getWorkflowId());
    }
}