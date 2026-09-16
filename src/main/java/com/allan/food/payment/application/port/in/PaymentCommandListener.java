package com.allan.food.payment.application.port.in;

import com.allan.food.saga.contract.SagaContract;

import static com.allan.food.saga.contract.SagaContract.*;

/**
 * Driving port through which saga commands enter the payment context.
 *
 * <p>The mirror of {@code PaymentCommandPort} on the order side: what the orchestrator sends, this receives.
 * The two interfaces are separated by the {@code SagaContract} module and by nothing else in Slice 2 — at Slice
 * 4 a Kafka topic sits between them, and neither interface changes.
 *
 * <p><b>Implementations must be idempotent, and here that means more than "do nothing on a duplicate."</b> If a
 * command arrives twice, the most likely explanation is that the <i>reply</i> to the first was lost — so the
 * correct response is to recognise the work as already done and <b>re-send the original reply</b>. A
 * participant that silently swallows duplicates leaves the orchestrator waiting forever for a message it will
 * never receive. See {@code PaymentService} for the implementation of that rule.
 */
public interface PaymentCommandListener {

    /** Charges the customer. Compensated by {@link #refund}. */
    void onProcessPayment(ProcessPayment command);

    /** Reverses a completed charge. The compensating transaction. */
    void onRefundPayment(RefundPayment command);
}
