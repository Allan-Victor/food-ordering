package com.allan.food.payment.application.port.out;

import com.allan.food.saga.contract.SagaContract;

import static com.allan.food.saga.contract.SagaContract.*;

/**
 * Driven port for sending outcomes back to the saga orchestrator.
 *
 * <p>Grouped by destination rather than split per reply, consistent with {@code PaymentCommandPort}: one
 * caller, one reason to change, one adapter, one eventual topic.
 *
 * <p><b>Called inside the handler's transaction</b>, with delivery deferred past commit by the adapter — the
 * same contract as {@code PublishEventPort}. A reply announcing a charge that then rolled back would be worse
 * than no reply at all, since the saga would advance on a lie.
 *
 * <p>Note there is no {@code refundFailed}. A compensating transaction has no failure branch by construction;
 * see {@code SagaContract.PaymentRefunded}.
 */
public interface PaymentReplyPort {

    void completed(PaymentCompleted reply);

    void failed(PaymentFailed reply);

    void refunded(PaymentRefunded reply);
}
