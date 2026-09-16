package com.allan.food.payment.application.service;

import com.allan.food.payment.application.port.in.PaymentCommandListener;
import com.allan.food.payment.application.port.out.PaymentPersistencePort;
import com.allan.food.payment.application.port.out.PaymentReplyPort;
import com.allan.food.payment.domain.model.CustomerCredit;
import com.allan.food.payment.domain.model.Money;
import com.allan.food.payment.domain.model.Payment;
import com.allan.food.payment.domain.model.PaymentStatus;
import com.allan.food.saga.contract.SagaContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.allan.food.payment.domain.model.PaymentStatus.*;
import static com.allan.food.saga.contract.SagaContract.*;

/**
 * The payment participant: executes saga commands against this context's aggregates and replies with the
 * outcome.
 *
 * <p><b>A participant is not a smaller orchestrator.</b> It holds no saga state, knows nothing of what comes
 * before or after it, and never decides what happens next. It is told to do one thing, does it, and says what
 * happened. That ignorance is what lets the same participant serve a different saga tomorrow without
 * modification, and it is the discipline most easily lost — the moment a participant branches on "which saga
 * am I in," the coordination has leaked out of the orchestrator.
 *
 * <h2>Idempotency, and the part that is easy to get wrong</h2>
 *
 * Every handler here will receive duplicate commands. The obvious half of the answer is to not charge twice.
 * The half that gets missed: <b>a duplicate command almost always means the reply was lost</b>, so the correct
 * response is to recognise the completed work and <i>re-send the original reply</i>. A participant that
 * silently ignores duplicates is worse than one that charges twice — the double charge is at least visible,
 * whereas the swallowed reply leaves the saga stalled forever with no error anywhere.
 *
 * <p>So each handler follows: look up prior work by {@code orderId} → if found, reply with the recorded outcome
 * and stop → otherwise do the work, record it, and reply.
 *
 * <p>The lookup is a check-then-act and therefore racy under concurrent duplicates; a unique constraint on
 * {@code payments.order_id} is what actually closes it. The loser's insert fails, its transaction rolls back,
 * the message is redelivered, and the retry then finds the committed record and replies from it. Application
 * logic and database constraint doing complementary halves of one job — the same shape as the state check and
 * {@code @Version} in the order saga.
 *
 * <h2>Failure is a reply, not an exception</h2>
 *
 * An unaffordable order produces {@code PaymentFailed}, not a thrown exception. This is the participant's most
 * important behavioural rule: <b>expected business outcomes travel as replies, and only genuine faults throw.</b>
 * A thrown exception rolls back and returns the message to the broker, which redelivers it, and a customer who
 * cannot afford an order will still not afford it on the third attempt — an ordinary outcome turned into a
 * poison message. Effective Java Item 69 states the general principle; a saga makes the cost of ignoring it
 * unusually concrete.
 */
@Service
class PaymentService implements PaymentCommandListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentPersistencePort persistence;
    private final PaymentReplyPort replyPort;
    private final Clock clock;

    PaymentService(PaymentPersistencePort persistence, PaymentReplyPort replyPort, Clock clock) {
        this.persistence = persistence;
        this.replyPort = replyPort;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Debits the customer if they can afford it, records the outcome either way, and replies.
     *
     * <p><b>Two aggregates change in one transaction</b> — {@link Payment} and {@link CustomerCredit}. Vernon's
     * rule is one aggregate per transaction, and this is a considered exception rather than an oversight: both
     * live in this context, in this database, so the atomicity is genuine. The rule guards against assuming
     * atomicity across boundaries where it does not exist, which is exactly the assumption the saga replaces
     * for the order/payment boundary. Recording a debit without the matching balance change would be a real
     * inconsistency, and here we can prevent it for free.
     */
    @Override
    @Transactional
    public void onProcessPayment(ProcessPayment command) {
        withContext(command.sagaId(), command.orderId(), () -> {

            Optional<Payment> existing = persistence.findPaymentByOrderId(command.orderId());
            if (existing.isPresent()) {
                replayOutcome(existing.get(), command.sagaId());
                return;
            }

            Money amount = new Money(command.amount(), command.currency());
            CustomerCredit credit = persistence.findCredit(command.customerId())
                    .orElseGet(() -> CustomerCredit.open(command.customerId(), Money.zero(command.currency())));

            if (!credit.canAfford(amount)) {
                // An ordinary business outcome. Recorded so a duplicate command replies identically
                // rather than re-attempting a charge that will fail again.
                persistence.savePayment(Payment.failed(
                        command.orderId(), command.customerId(), amount, clock.instant()));

                log.info("Payment refused: balance {} does not cover {}", credit.balance(), amount);

                replyPort.failed(new PaymentFailed(
                        command.sagaId(), command.orderId(),
                        List.of("Insufficient funds: balance %s, required %s"
                                .formatted(credit.balance(), amount)),
                        clock.instant()));
                return;
            }

            credit.debit(amount);
            persistence.saveCredit(credit);

            Payment payment = persistence.savePayment(Payment.completed(
                    command.orderId(), command.customerId(), amount, clock.instant()));

            log.info("Payment {} settled for {}; balance now {}",
                    payment.paymentId(), amount, credit.balance());

            replyPort.completed(new PaymentCompleted(
                    command.sagaId(), command.orderId(), payment.paymentId(), clock.instant()));
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reverses a completed charge and restores the balance. The compensating transaction for
     * {@link #onProcessPayment}.
     *
     * <p><b>Every branch here replies, including the anomalous ones</b>, and that is a deliberate departure
     * from how the orchestrator treats anomalies. The orchestrator absorbs a reply it cannot use, because a
     * reply is a notification and dropping it stalls nothing. A compensation command is different: the order is
     * sitting in {@code CANCELLING} waiting to be released, and a participant that stays silent leaves it there
     * permanently. So an already-refunded payment replies {@code PaymentRefunded} again, and even a refund for
     * a payment that never completed replies rather than hanging — the saga must be able to finish even when
     * something has gone wrong upstream.
     *
     * <p>This asymmetry is worth internalising: <b>silence is safe on the reply path and dangerous on the
     * command path.</b>
     */
    @Override
    @Transactional
    public void onRefundPayment(RefundPayment command) {
        withContext(command.sagaId(), command.orderId(), () -> {

            Optional<Payment> found = persistence.findPaymentByOrderId(command.orderId());
            if (found.isEmpty()) {
                // Nothing was ever charged, so nothing needs reversing. Still reply, or the order
                // never leaves CANCELLING.
                log.error("Refund requested for order with no payment record; replying to unblock the saga");
                replyPort.refunded(new PaymentRefunded(command.sagaId(), command.orderId(), clock.instant()));
                return;
            }

            Payment payment = found.get();

            if (payment.status() == PaymentStatus.REFUNDED) {
                log.debug("Duplicate refund for already-refunded payment {}; re-sending reply",
                        payment.paymentId());
                replyPort.refunded(new PaymentRefunded(command.sagaId(), command.orderId(), clock.instant()));
                return;
            }

            if (payment.status() == PaymentStatus.FAILED) {
                log.error("Refund requested for a payment that never completed ({}); no money to return",
                        payment.paymentId());
                replyPort.refunded(new PaymentRefunded(command.sagaId(), command.orderId(), clock.instant()));
                return;
            }

            payment.markRefunded();
            persistence.savePayment(payment);

            CustomerCredit credit = persistence.findCredit(payment.customerId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Payment %s exists without a credit record for customer %s"
                                    .formatted(payment.paymentId(), payment.customerId())));

            credit.credit(payment.amount());
            persistence.saveCredit(credit);

            log.info("Payment {} refunded; balance restored to {}", payment.paymentId(), credit.balance());

            replyPort.refunded(new PaymentRefunded(command.sagaId(), command.orderId(), clock.instant()));
        });
    }

    /**
     * Re-sends the reply for work already done.
     *
     * <p>The other half of participant idempotency. A duplicate command means the first reply probably never
     * arrived, so recognising the duplicate is only useful if we also say again what happened.
     *
     * <p>A refunded payment replies {@code PaymentCompleted}: the duplicate is a repeat of the <i>original</i>
     * charge command, and the answer to "did this charge succeed?" is still yes. The refund was a separate
     * command with its own reply. Answering the question that was asked, not describing current state.
     */
    private void replayOutcome(Payment payment, UUID sagaId) {
        switch (payment.status()) {
            case COMPLETED, REFUNDED -> {
                log.debug("Duplicate ProcessPayment for payment {}; re-sending completion",
                        payment.paymentId());
                replyPort.completed(new PaymentCompleted(
                        sagaId, payment.orderId(), payment.paymentId(), clock.instant()));
            }
            case FAILED -> {
                log.debug("Duplicate ProcessPayment for previously-failed payment {}; re-sending failure",
                        payment.paymentId());
                replyPort.failed(new PaymentFailed(
                        sagaId, payment.orderId(),
                        List.of("Insufficient funds"), clock.instant()));
            }
        }
    }

    /** Binds saga correlation ids to the logging context — see {@code OrderSaga.withSagaContext}. */
    private void withContext(UUID sagaId, UUID orderId, Runnable work) {
        MDC.put("sagaId", sagaId.toString());
        MDC.put("orderId", orderId.toString());
        try {
            work.run();
        } finally {
            MDC.remove("sagaId");
            MDC.remove("orderId");
        }
    }
}
