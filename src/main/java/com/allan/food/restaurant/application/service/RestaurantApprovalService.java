package com.allan.food.restaurant.application.service;

import com.allan.food.restaurant.application.port.in.RestaurantCommandListener;
import com.allan.food.restaurant.application.port.out.ApprovalPersistencePort;
import com.allan.food.restaurant.application.port.out.RestaurantReplyPort;
import com.allan.food.restaurant.domain.model.OrderApproval;
import com.allan.food.restaurant.domain.model.RestaurantAvailability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.allan.food.saga.contract.SagaContract.*;

/**
 * The restaurant participant: decides the pivot and replies.
 *
 * <p><b>The shortest service in the system, and that is deliberate rather than incidental.</b> A pivot step
 * should do as little as possible, because everything it does is irreversible. Payment could afford some
 * latitude — a debit can be credited back, so ordering mistakes are recoverable. Here every guard runs before
 * the decision commits, because after it commits there is no guard left to run.
 *
 * <p><b>Holds no saga state and makes no forward decisions.</b> It does not know that payment preceded it, nor
 * that a rejection will trigger a refund. It is asked one question, answers it, and stops. That ignorance is
 * what would let this same participant serve a different saga unchanged, and it is the discipline that leaks
 * first: the moment a participant branches on which flow it is in, coordination has escaped the orchestrator.
 *
 * <h2>Idempotency</h2>
 *
 * The rule established on {@code PaymentService} applies with more force. A duplicate command means the reply
 * was probably lost, so the handler recognises the recorded decision and <i>re-sends the same reply</i>.
 *
 * <p>Re-deciding would be worse here than in payment, and the reason is specific to this context: availability
 * is volatile. The same order evaluated twice, minutes apart, can legitimately reach opposite conclusions — a
 * dish sells out between attempts. A participant that re-decides would send the saga two contradictory replies
 * for one command, and the saga has no way to tell which is authoritative. <b>Persisting the decision is what
 * makes the answer stable, not merely what makes it fast.</b>
 *
 * <h2>Rejection is a reply, never an exception</h2>
 *
 * A refusal is an expected outcome the saga is built to compensate. Throwing would roll back, return the
 * message to the broker, and redeliver it — and since the kitchen will still be closed on the next attempt, an
 * ordinary rejection becomes a poison message blocking its partition. Only genuine faults propagate.
 */
@Service
class RestaurantApprovalService implements RestaurantCommandListener {

    private static final Logger log = LoggerFactory.getLogger(RestaurantApprovalService.class);

    private final ApprovalPersistencePort persistence;
    private final RestaurantReplyPort replyPort;
    private final Clock clock;

    RestaurantApprovalService(ApprovalPersistencePort persistence, RestaurantReplyPort replyPort, Clock clock) {
        this.persistence = persistence;
        this.replyPort = replyPort;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Checks availability, records the decision, replies. One aggregate written, one transaction, no
     * subsequent state to manage.
     *
     * <p><b>Order within the method is load-bearing in a way it was not for payment.</b> The decision is
     * persisted <i>before</i> the reply is issued, so a crash between them leaves a durable decision that a
     * redelivered command will replay correctly. The reverse order would let the saga learn of an approval that
     * this context has no record of making — and since the saga would then run forward past its last recovery
     * point, that inconsistency would be permanent.
     *
     * <p>An unknown restaurant rejects rather than throwing. It means the order referenced a restaurant this
     * context does not know, which retrying cannot fix; rejecting sends the saga down its compensation path,
     * which is the correct handling of an order that cannot be fulfilled.
     */
    @Override
    @Transactional
    public void onApproveOrder(ApproveOrder command) {
        withContext(command.sagaId(), command.orderId(),
                ()-> {
                    Optional<OrderApproval> existing = persistence.findApprovalByOrderId(command.orderId());
                    if (existing.isPresent()) {
                        replayDecision(existing.get(), command.sagaId());
                        return;
                    }

                    Optional<RestaurantAvailability> found = persistence.findAvailability(command.restaurantId());
                    if (found.isEmpty()) {
                        reject(command, List.of("Restaurant %s is not known to this service"
                                .formatted(command.restaurantId())));
                        return;
                    }

                    List<UUID> requestedProducts = command.lines().stream()
                            .map(ApproveOrder.Line::productId)
                            .toList();

                    RestaurantAvailability.AvailabilityCheck check = found.get().check(requestedProducts);
                    if (!check.satisfied()) {
                        reject(command, check.reasons());
                        return;
                    }

                    persistence.saveApproval(
                            OrderApproval.approved(command.orderId(), command.restaurantId(), clock.instant()));

                    log.info("Order approved for preparation ({} lines); pivot committed", command.lines().size());

                    replyPort.approved(new OrderApproved(command.sagaId(), command.orderId(), clock.instant()));
                });
    }

    /**
     * Records a refusal and tells the saga why.
     *
     * <p>Extracted because rejection has three call sites and each must both persist and reply — a path where
     * one is easy to forget, and where forgetting the persist means a redelivered command re-decides against
     * volatile availability, while forgetting the reply strands the order permanently.
     */
    private void reject(ApproveOrder command, List<String> reasons) {
        persistence.saveApproval(OrderApproval.rejected(
                command.orderId(), command.restaurantId(), reasons, clock.instant()));

        log.info("Order rejected: {}", reasons);

        replyPort.rejected(new OrderRejected(
                command.sagaId(), command.orderId(), reasons, clock.instant()));
    }
    /**
     * Re-sends the reply for a decision already made.
     *
     * <p>The half of idempotency that gets omitted. Recognising the duplicate is only useful if we also say
     * again what was decided — otherwise the orchestrator waits forever for a reply that was sent once and lost.
     *
     * <p>An exhaustive {@code switch} over the enum, with no {@code default}: adding a third status would then
     * be a compile error here rather than a silently unhandled branch. Given that a third status would mean
     * someone had made the pivot reversible, failing to compile is exactly the right outcome.
     */
    private void replayDecision(OrderApproval approval, UUID sagaId) {
        log.debug("Duplicate ApproveOrder for order already {}; re-sending reply", approval.status());

        switch (approval.status()) {
            case APPROVED -> replyPort.approved(new OrderApproved(
                    sagaId, approval.orderId(), clock.instant()));

            case REJECTED -> replyPort.rejected(new OrderRejected(
                    sagaId, approval.orderId(), approval.reasons(), clock.instant()));
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
