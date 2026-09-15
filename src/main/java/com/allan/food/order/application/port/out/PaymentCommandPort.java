package com.allan.food.order.application.port.out;

import com.allan.food.saga.contract.SagaContract.ProcessPayment;
import com.allan.food.saga.contract.SagaContract.RefundPayment;

/**
 * Driven port for issuing commands to the payment participant.
 *
 * <p><b>Grouped by participant, not split per command</b> — a deviation from the one-capability-per-port rule
 * we applied to {@code SaveOrderPort} and {@code LoadOrderPort}, and worth justifying. Those split because
 * different callers need different halves. Here there is one caller, the orchestrator, and one reason to
 * change: the payment participant's contract. At Slice 4 both commands travel to the same Kafka topic through
 * the same adapter and the same serialiser. Splitting would produce two interfaces with identical
 * implementations and identical reasons to change, which is ceremony rather than segregation.
 *
 * <p><b>Both methods are one-way.</b> No return value, no {@code Future}. A saga step's outcome arrives later
 * as a separate reply on {@link com.allan.food.order.application.port.in.PaymentReplyListener}, in a new
 * transaction. Anything that returned a result here would be a synchronous call wearing a saga's clothes, and
 * the temptation to write {@code if (paymentPort.process(...))} is exactly the mistake the pattern exists to
 * prevent.
 */
public interface PaymentCommandPort {

    /** Requests a charge. Compensatable — see {@link #refund}. */
    void process(ProcessPayment command);

    /**
     * Requests reversal of a completed charge.
     *
     * <p>The compensating transaction. Issued only after the order has entered {@code CANCELLING}, so that the
     * aggregate is visibly locked while compensation is in flight.
     */
    void refund(RefundPayment command);
}
