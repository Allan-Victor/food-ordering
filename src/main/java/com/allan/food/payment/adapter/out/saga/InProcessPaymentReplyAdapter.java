package com.allan.food.payment.adapter.out.saga;

import com.allan.food.payment.application.port.out.PaymentReplyPort;
import com.allan.food.saga.contract.SagaContract;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Slice 2 transport for payment replies.
 *
 * <p>Called inside the participant's transaction, delivered after it commits — so the orchestrator never learns
 * of a charge that rolled back. Same mechanism, same durability caveat, and the same replacement path at Slice
 * 3 as the command adapters.
 */
@Component("paymentReplyAdapter")
class InProcessPaymentReplyAdapter implements PaymentReplyPort {

    private final ApplicationEventPublisher publisher;

    InProcessPaymentReplyAdapter(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void completed(SagaContract.PaymentCompleted reply) {
        publisher.publishEvent(reply);
    }

    @Override
    public void failed(SagaContract.PaymentFailed reply) {
        publisher.publishEvent(reply);
    }

    @Override
    public void refunded(SagaContract.PaymentRefunded reply) {
        publisher.publishEvent(reply);
    }
}
