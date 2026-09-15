package com.allan.food.order.application.port.in;

import com.allan.food.saga.contract.SagaContract.PaymentCompleted;
import com.allan.food.saga.contract.SagaContract.PaymentFailed;
import com.allan.food.saga.contract.SagaContract.PaymentRefunded;

/**
 * Driving port through which payment replies enter the application and advance the saga.
 *
 * <p><b>A method per reply, rather than one method taking a sealed type.</b> The opposite of the call we made
 * for {@code PublishEventPort}, and for a symmetric reason. There, one method taking the sealed
 * {@code DomainEvent} was right because the adapter's job was to <i>route</i> uniformly. Here each reply drives
 * a different transition through a different branch of the state machine, so distinct methods make the machine
 * legible at the interface: three replies, three transitions, visible without opening the implementation. This
 * is the shape the reference uses and it is the right one.
 *
 * <p><b>Named for the messaging idiom rather than as a use case.</b> Our other driving ports read as use cases
 * ({@code CreateOrderUseCase}); these read as listeners. The difference is honest — a human places an order,
 * whereas nobody <i>wants</i> to handle a payment reply. It is a continuation of work already in progress, and
 * naming it a use case would overstate it.
 *
 * <p><b>Implementations must be idempotent.</b> Delivery is at-least-once, so every method here will eventually
 * be called twice with the same message. A duplicate must be absorbed silently, not rejected: throwing would
 * make the broker redeliver forever, turning an ordinary duplicate into a poison message. Note the tension with
 * {@code Order.pay()}, which throws on a repeat — correct there, wrong here. The aggregate defends an
 * invariant; the saga absorbs the transport's guarantees.
 */
public interface PaymentReplyListener {

    /** Payment succeeded: advance {@code PENDING → PAID} and issue the pivot command. */
    void onPaymentCompleted(PaymentCompleted reply);

    /** Payment failed: no compensation needed, so cancel the order outright. */
    void onPaymentFailed(PaymentFailed reply);

    /** Compensation settled: release {@code CANCELLING → CANCELLED}. */
    void onPaymentRefunded(PaymentRefunded reply);


}
