package com.allan.food.payment.adapter.in.saga;

import com.allan.food.payment.application.port.in.PaymentCommandListener;
import com.allan.food.saga.contract.SagaContract;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Driving adapter delivering saga commands into the payment context.
 *
 * <p><b>{@code REQUIRES_NEW} here, unlike the order context's reply adapter, and the reason is worth
 * understanding rather than copying.</b> {@code AFTER_COMMIT} fires when the publishing transaction has already
 * ended, so a listener at that phase runs with no transaction bound to the thread. The order side gets away
 * without it because {@code OrderSaga}'s handlers are themselves {@code @Transactional} and start one. Here the
 * service's own {@code @Transactional} would do the same — so this is belt and braces, made explicit at the
 * boundary where the transaction genuinely begins.
 *
 * <p>The reason to be explicit rather than rely on the service: this is the seam Slice 3 changes. When the
 * outbox relay drives participants instead of the event bus, the transaction demarcation moves here and having
 * it already stated means the change is a substitution rather than a discovery. Getting it wrong fails loudly
 * — {@code TransactionRequiredException} on the first write — which is the good kind of wrong, but only if you
 * know to expect it.
 *
 * <p><b>Note this class shares a simple name with the order context's outbound adapter.</b> Different packages,
 * opposite directions, one sends and one receives. Deliberate: the pairing makes the transport symmetry visible
 * across the two contexts, and the packages ({@code adapter.out.saga} against {@code adapter.in.saga}) say
 * which is which.
 */
@Component("paymentCommandAdapter")
class InProcessPaymentCommandAdapter {

    private final PaymentCommandListener listener;

    InProcessPaymentCommandAdapter(PaymentCommandListener listener) {
        this.listener = listener;
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(SagaContract.ProcessPayment command) {
        listener.onProcessPayment(command);
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(SagaContract.RefundPayment command) {
        listener.onRefundPayment(command);
    }
}
