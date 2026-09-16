package com.allan.food.order.adapter.out.saga;

import com.allan.food.order.application.port.out.PaymentCommandPort;
import com.allan.food.saga.contract.SagaContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Slice 2 transport for payment commands: Spring's in-process event multicaster.
 *
 * <p><b>Read the name as a statement of scope.</b> This adapter delivers within one JVM with no durability
 * guarantee — a crash between commit and delivery loses the command and strands the saga. Acceptable now
 * because the point of Slice 2 is the saga's <i>logic</i>; unacceptable from Slice 3, where the outbox makes
 * command and state atomic. The port is unchanged by that replacement, which is the return on having stated the
 * dependency as one.
 *
 * <p><b>Why publishing inside the caller's transaction is nonetheless safe.</b> The multicaster holds the event
 * until the transaction phase the listener asked for. The participant subscribes with
 * {@code @TransactionalEventListener}, whose default phase is {@code AFTER_COMMIT}, so a rolled-back order
 * never results in a charge. This is the same guarantee argued at length on {@code PublishEventPort}, and it is
 * what let us drop the reference's {@code OrderCreateHelper} indirection.
 *
 * <p><b>The commands are published as themselves, not wrapped.</b> Spring routes events by type, and our
 * command records are distinct types, so {@code ProcessPayment} and {@code RefundPayment} reach different
 * listeners with no discriminator, no envelope and no cast. At Slice 4 the adapter gains a destination per type
 * and the participant gains a deserialiser; the records crossing between them do not change.
 */
@Component("orderPaymentCommandAdapter")
class InProcessPaymentCommandAdapter implements PaymentCommandPort {

    private static final Logger log = LoggerFactory.getLogger(InProcessPaymentCommandAdapter.class);

    private final ApplicationEventPublisher publisher;

    InProcessPaymentCommandAdapter(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void process(SagaContract.ProcessPayment command) {
        log.debug("Dispatching ProcessPayment for order {}", command.orderId());
        publisher.publishEvent(command);
    }

    @Override
    public void refund(SagaContract.RefundPayment command) {
        log.debug("Dispatching RefundPayment for order {}", command.orderId());
        publisher.publishEvent(command);
    }
}
