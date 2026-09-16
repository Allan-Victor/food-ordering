package com.allan.food.order.adapter.in.saga;

import com.allan.food.order.application.port.in.PaymentReplyListener;
import com.allan.food.order.application.port.in.RestaurantReplyListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import static com.allan.food.saga.contract.SagaContract.*;

/**
 * Driving adapter delivering participant replies into the order context.
 *
 * <p><b>The counterpart of {@code OrderController}, for a different transport.</b> The controller turns HTTP
 * into port calls; this turns in-process events into port calls. Neither contains logic, and the orchestrator
 * behind them cannot tell which one invoked it — which is precisely what will let Slice 4 replace this class
 * with a Kafka consumer while {@code OrderSaga} stays byte-identical.
 *
 * <p><b>One adapter for both reply ports, because they share a transport.</b> Ports split by reason to depend;
 * adapters group by technology. At Slice 4 payment and restaurant replies arrive on different topics with
 * different deserialisers, and that is the point at which this splits in two — driven by the transport
 * diverging, not by tidiness.
 *
 * <p><b>Every method is {@code @TransactionalEventListener} with no explicit phase</b>, so the default
 * {@code AFTER_COMMIT} applies: a reply is not acted on until the participant's own work is durable. Note the
 * absence of {@code @Transactional} here — the orchestrator's handlers carry their own, so this adapter
 * genuinely does nothing but forward. {@code OrderSaga.onOrderCreated} is the exception that needs
 * {@code REQUIRES_NEW}, because it is triggered from a phase where no transaction exists.
 *
 * Every @TransactionalEventListener that drives database writes must carry
 * @Transactional(REQUIRES_NEW) — the publishing transaction has ended by the time
 * this fires, and REQUIRED finds nothing to join.
 *
 * <p><b>Public methods on a package-private class</b> — Spring's event listener detection, like its transaction
 * interception, ignores non-public methods. A package-private listener would simply never fire, with no error
 * to explain why.
 */
@Component
class InProcessSagaReplyAdapter {
    private final PaymentReplyListener paymentReplyListener;
    private final RestaurantReplyListener restaurantReplyListener;


    InProcessSagaReplyAdapter(PaymentReplyListener paymentReplyListener, RestaurantReplyListener restaurantReplyListener) {
        this.paymentReplyListener = paymentReplyListener;
        this.restaurantReplyListener = restaurantReplyListener;
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(PaymentCompleted reply) {
        paymentReplyListener.onPaymentCompleted(reply);
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(PaymentFailed reply) {
        paymentReplyListener.onPaymentFailed(reply);
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(PaymentRefunded reply) {
        paymentReplyListener.onPaymentRefunded(reply);
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(OrderApproved reply) {
        restaurantReplyListener.onOrderApproved(reply);
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(OrderRejected reply) {
        restaurantReplyListener.onOrderRejected(reply);
    }
}
