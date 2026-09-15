package com.allan.food.order.application.saga;

import com.allan.food.order.application.port.in.PaymentReplyListener;
import com.allan.food.order.application.port.in.RestaurantReplyListener;
import com.allan.food.order.application.port.out.LoadOrderPort;
import com.allan.food.order.application.port.out.PaymentCommandPort;
import com.allan.food.order.application.port.out.RestaurantCommandPort;
import com.allan.food.order.application.port.out.SaveOrderPort;
import com.allan.food.order.domain.event.OrderCreatedEvent;
import com.allan.food.order.domain.model.entity.Order;
import com.allan.food.order.domain.model.valueobject.OrderStatus;
import com.allan.food.saga.contract.SagaContract.ApproveOrder;
import com.allan.food.saga.contract.SagaContract.OrderApproved;
import com.allan.food.saga.contract.SagaContract.OrderRejected;
import com.allan.food.saga.contract.SagaContract.PaymentCompleted;
import com.allan.food.saga.contract.SagaContract.PaymentFailed;
import com.allan.food.saga.contract.SagaContract.PaymentRefunded;
import com.allan.food.saga.contract.SagaContract.ProcessPayment;
import com.allan.food.saga.contract.SagaContract.RefundPayment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static com.allan.food.order.domain.model.valueobject.OrderStatus.APPROVED;
import static com.allan.food.order.domain.model.valueobject.OrderStatus.CANCELLED;
import static com.allan.food.order.domain.model.valueobject.OrderStatus.CANCELLING;
import static com.allan.food.order.domain.model.valueobject.OrderStatus.PAID;
import static com.allan.food.order.domain.model.valueobject.OrderStatus.PENDING;


/**
 * The order saga orchestrator: a Process Manager coordinating payment and restaurant approval across bounded
 * contexts, with no distributed transaction anywhere.
 *
 * <h2>What a saga is, and why this class exists</h2>
 *
 * A single order touches three consistency boundaries — the order aggregate, the payment context, the
 * restaurant context. A distributed transaction across all three would require two-phase commit, which holds
 * locks across services for the duration and makes availability the product of every participant's
 * availability. Richardson's answer, and the industry's, is to give up atomicity and keep consistency: run a
 * <i>sequence of local transactions</i>, each committing independently, and undo the earlier ones with explicit
 * compensating transactions when a later one fails.
 *
 * <p>The cost is real and must be understood rather than glossed: the system is observably inconsistent between
 * steps. An order can be paid but not yet approved. There is no isolation, so a concurrent reader sees
 * intermediate states. Sagas trade the illusion of atomicity for availability, and that trade only works if the
 * intermediate states are modelled deliberately — which is exactly what {@code CANCELLING} is.
 *
 * <h2>Orchestration, not choreography</h2>
 *
 * Two ways to run a saga. In <b>choreography</b>, each participant listens for the previous participant's event
 * and reacts; there is no coordinator, and the state machine exists only as an emergent property of who
 * subscribes to what. In <b>orchestration</b>, one component holds the state machine and tells each participant
 * what to do.
 *
 * <p>We orchestrate, and the reason is legibility. Every transition in this saga is a method in this file; the
 * whole machine can be read top to bottom. Choreography distributes that logic across three modules, so
 * answering "what happens when the restaurant rejects?" means reading three codebases and reconstructing the
 * flow by hand. The price of orchestration is a component that knows about all participants — genuine coupling,
 * and the reason choreography is preferred where the flow is simple and the services must stay independent.
 * The {@code saga/choreography} branch implements the same flow the other way for comparison.
 *
 * <h2>The state machine</h2>
 *
 * <pre>
 *                              PENDING
 *                                 │
 *              PaymentCompleted   │   PaymentFailed
 *                    ┌────────────┴────────────┐
 *                    ▼                         ▼
 *                  PAID                    CANCELLED (terminal)
 *                    │
 *     OrderApproved  │  OrderRejected
 *          ┌─────────┴─────────┐
 *          ▼                   ▼
 *      APPROVED            CANCELLING
 *     (terminal)               │  PaymentRefunded
 *                              ▼
 *                          CANCELLED (terminal)
 * </pre>
 *
 * Three steps, classified per {@link OrderSagaStep}: payment is <b>compensatable</b>, restaurant approval is
 * the <b>pivot</b>, the final order approval is <b>retriable</b>. Compensatables first, then the pivot, then
 * retriables — the ordering that makes the saga recoverable, because every step that might need undoing has
 * already happened by the time the pivot commits.
 *
 * <h2>Why there is no separate saga-state field</h2>
 *
 * {@link OrderStatus} <i>is</i> the saga state. We arrived at {@code PENDING → PAID → APPROVED} with
 * {@code CANCELLING} as a compensation lock before we knew we were designing a saga, which is not a
 * coincidence: the aggregate's lifecycle and the saga's progress are the same fact viewed twice. The reference
 * course carries a parallel {@code SagaStatus} enum alongside {@code OrderStatus}, which creates two fields
 * describing one process, an invariant that they agree, and no code enforcing it.
 *
 * <p><b>Where this will break, honestly.</b> {@code PENDING} cannot distinguish "payment command not yet
 * issued" from "payment command issued, awaiting reply." Timeout detection needs that distinction, so Slice 3
 * introduces a persisted saga instance — driven by a real requirement rather than by symmetry.
 *
 * <h2>Transaction boundaries</h2>
 *
 * Every handler here is its own transaction: load, transition, save, issue the next command, commit. Nothing
 * spans two steps. A transaction held across the whole saga would be a distributed transaction with extra
 * steps, defeating the entire point.
 *
 * <p>Commands are issued <i>inside</i> the step's transaction, which is safe for the same reason it was safe in
 * {@code CreateOrderService}: the in-process adapters defer actual delivery past commit, and at Slice 3 the
 * outbox makes state and command atomic. See {@code PublishEventPort} for the full argument.
 *
 * <h2>Idempotency — the detail most saga tutorials skip</h2>
 *
 * Message delivery is at-least-once, so every handler here <i>will</i> receive duplicates. The naive
 * implementation calls straight into the aggregate, {@code Order.pay()} throws because the order is no longer
 * {@code PENDING}, the handler propagates, the broker redelivers, and an ordinary duplicate has become a poison
 * message that blocks its partition forever.
 *
 * <p>So each handler checks state <i>before</i> touching the aggregate and absorbs anything that does not
 * advance the machine. Note carefully what this means: <b>the aggregate rejects a repeated transition and the
 * saga absorbs it, and both are correct.</b> {@code Order} defends an invariant and must be strict.
 * {@code OrderSaga} mediates an unreliable transport and must be tolerant. Collapsing the two — loosening the
 * aggregate so the saga can be naive — is the mistake this separation prevents.
 *
 * <p>Concurrency is the other half. Two duplicates processed simultaneously both pass the state check, so the
 * check alone is insufficient; the aggregate's {@code @Version} is what makes the second write fail. An
 * {@code OptimisticLockingFailureException} is deliberately <i>not</i> caught here — it propagates, the message
 * is redelivered, and on redelivery the state check absorbs it. The two mechanisms compose: the version detects
 * the race, the state check resolves the retry.
 *
 * <h2>Scope</h2>
 *
 * Not implemented here, each for a stated reason: timeouts and stuck-saga detection (needs the persisted saga
 * instance, Slice 3); a dead-letter path for anomalous replies (needs the messaging adapter, Slice 4); and
 * retry with backoff for retriable steps (the broker's concern, configured in the binder, not hand-rolled
 * here).
 */
@Service
class OrderSaga implements PaymentReplyListener, RestaurantReplyListener {

    private static final Logger log = LoggerFactory.getLogger(OrderSaga.class);

    /** Mapped Diagnostic Context (MDC) keys.
     * {@link <a href="https://logback.qos.ch/manual/mdc.html">logback MDC</a>}
     * Every log line inside a handler carries both, so one saga's journey greps as a unit. */
    private static final String MDC_SAGA_ID = "sagaId";
    private static final String MDC_ORDER_ID = "orderId";

    private final LoadOrderPort loadOrderPort;
    private final SaveOrderPort saveOrderPort;
    private final PaymentCommandPort paymentCommandPort;
    private final RestaurantCommandPort restaurantCommandPort;
    private final Clock clock;


    OrderSaga(LoadOrderPort loadOrderPort,
              SaveOrderPort saveOrderPort,
              PaymentCommandPort paymentCommandPort,
              RestaurantCommandPort restaurantCommandPort,
              Clock clock) {
        this.loadOrderPort = loadOrderPort;
        this.saveOrderPort = saveOrderPort;
        this.paymentCommandPort = paymentCommandPort;
        this.restaurantCommandPort = restaurantCommandPort;
        this.clock = clock;

    }


    // ─────────────────────────────────────────────────────────────────────
    //  Step 1 — start: issue the payment command  (compensatable)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Starts the saga once a new order has been durably committed.
     *
     * <p><b>{@code AFTER_COMMIT} is the whole point of listening this way.</b> Asking a payment provider to
     * charge a customer for an order that then rolls back is precisely the failure the phase exists to prevent.
     * The order is durable before anyone is asked to act on it.
     *
     * <p><b>{@code REQUIRES_NEW}, because after commit there is no transaction.</b> The previous one has ended
     * by definition. In Slice 2 the command port publishes in-process and needs no transaction, so this opens
     * an empty one — a small, deliberate waste that buys two things: the invariant "every saga step is its own
     * transaction" becomes true in code rather than by accident, and Slice 3's outbox write has a boundary
     * waiting for it instead of a subtle bug to find.
     *
     * <p><b>{@code public}, unlike the class.</b> Spring's transaction attribute source ignores non-public
     * methods by default, so a package-private handler would silently lose its {@code @Transactional} — a
     * failure with no error message. The interface methods below are public for free; this one must say so.
     *
     * <p><b>The saga starts here rather than in {@code CreateOrderService}</b> so the entire state machine,
     * including its entry point, lives in one file. The cost is that this class knows Spring's event
     * mechanism; at Slice 4 the trigger becomes an inbound messaging adapter and this annotation is replaced,
     * while the method body is unchanged.
     *
     * <p><b>The saga id is minted here</b> and echoed by every participant through the rest of the flow. It is
     * not persisted in Slice 2 and not used for correlation — {@code orderId} does that work. Its job today is
     * tracing; its job from Slice 3 is to be the saga instance's primary key.
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderCreated(OrderCreatedEvent event) {
        UUID sagaId = UUID.randomUUID();

        withSagaContext(sagaId, event.orderId(), () -> {
            log.info("Saga started: requesting payment of {} {}",
                    event.price().amount(), event.price().currency());

            paymentCommandPort.process(toProcessPayment(sagaId, event));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Step 2 — payment replies
    // ─────────────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>{@code PENDING → PAID}, then issue the pivot command. The compensatable step has succeeded; from here
     * a later failure must actively undo it.
     */
    @Transactional
    @Override
    public void onPaymentCompleted(PaymentCompleted reply) {
        handleReply(reply.sagaId(), reply.orderId(), PENDING, Set.of(PAID, APPROVED), "PaymentCompleted", order -> {
            order.pay();
            Order saved = saveOrderPort.save(order);

            restaurantCommandPort.approve(toApproveOrder(reply.sagaId(), saved));

            log.info("Payment {} settled: requesting restaurant approval (pivot)",
                    reply.paymentId());
        });

    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code PENDING → CANCELLED}, terminal, with no compensation issued.
     *
     * <p><b>Why nothing is compensated.</b> Payment is the first step, so no earlier step has produced an
     * external effect that needs undoing. This is the practical payoff of ordering compensatables first: the
     * earliest failures are the cheapest to handle. Compare {@link #onOrderRejected}, where a failure at the
     * pivot has a completed charge behind it.
     */
    @Transactional
    @Override
    public void onPaymentFailed(PaymentFailed reply) {
        handleReply(reply.sagaId(), reply.orderId(), PENDING, Set.of(CANCELLED), "PaymentFailed", order -> {
            order.cancel(reply.reasons());
            saveOrderPort.save(order);

            log.info("Payment failed, order cancelled with no compensation required: {}",
                    reply.reasons());
        });

    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code CANCELLING → CANCELLED}. Compensation has settled and the semantic lock is released.
     *
     * <p>No failure reason is added: the reasons were recorded when the order entered {@code CANCELLING}, and a
     * compensating transaction completing successfully is not itself a failure.
     */
    @Transactional
    @Override
    public void onPaymentRefunded(PaymentRefunded reply) {
        handleReply(reply.sagaId(), reply.orderId(), CANCELLING, Set.of(CANCELLED), "PaymentRefunded", order -> {
            order.cancel(List.of());
            saveOrderPort.save(order);

            log.info("Compensation complete: saga ended in CANCELLED");
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Step 3 — restaurant replies (the pivot resolves)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>{@code PAID → APPROVED}, terminal, saga complete.
     *
     * <p><b>The pivot has committed.</b> Food is being prepared and no message can un-prepare it, so backward
     * recovery is no longer available for anything in this saga. This final transition is therefore
     * <i>retriable</i> rather than compensatable — and it is deliberately trivial, a local state change on an
     * aggregate already in hand, because a step after the pivot must be one that retrying can actually fix.
     * Designing the tail of a saga to be boring is how you keep it safe.
     */
    @Transactional
    @Override
    public void onOrderApproved(OrderApproved reply) {
        handleReply(reply.sagaId(), reply.orderId(), PAID, Set.of(APPROVED), "OrderApproved", order -> {
            order.approve();
            saveOrderPort.save(order);

            log.info("Restaurant approved: saga complete");
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code PAID → CANCELLING}, then compensate the payment. The last point at which this saga can go
     * backwards.
     *
     * <p><b>Why two phases rather than cancelling outright.</b> The refund is asynchronous — it may take
     * seconds or days. Moving straight to {@code CANCELLED} would claim the order is fully undone while the
     * customer's money is still held. {@code CANCELLING} is a semantic lock stating "compensation in flight":
     * it blocks any other transition, it is visible to the customer through the tracking endpoint, and it gives
     * {@link #onPaymentRefunded} a state to legitimately arrive at. Adopted from the reference, which gets this
     * right.
     *
     * <p>Order matters: the aggregate is saved <i>before</i> the refund command is issued, so the lock is
     * durable before anyone can act on it.
     */
    @Transactional
    @Override
    public void onOrderRejected(OrderRejected reply) {
        handleReply(reply.sagaId(), reply.orderId(), PAID, Set.of(CANCELLING, CANCELLED), "OrderRejected", order -> {
            order.initCancel(reply.reasons());
            Order saved = saveOrderPort.save(order);

            paymentCommandPort.refund(toRefundPayment(reply.sagaId(), saved));

            log.info("Restaurant rejected ({}): compensating payment", reply.reasons());
        });

    }

    // ─────────────────────────────────────────────────────────────────────
    //  Shared mechanics
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Handles a reply event by bounding MDC context, verifying state idempotency, and delegating the transition.
     */
    private void handleReply(UUID sagaId,
                             UUID orderId,
                             OrderStatus required,
                             Set<OrderStatus> alreadyApplied,
                             String replyType,
                             Consumer<Order> action) {
        withSagaContext(sagaId, orderId, () ->
                loadForTransition(orderId, required, alreadyApplied, replyType)
                        .ifPresent(action));
    }

    /**
     * Loads the order and decides whether this reply should advance the machine.
     *
     * <p>The single place idempotency is implemented, so no handler can forget it and every handler reads as
     * the transition it performs rather than as a pile of defensive checks. Three outcomes:
     *
     * <ul>
     *   <li><b>The expected state</b> — return the order; the caller transitions it.</li>
     *   <li><b>A state this reply would have produced</b> — a duplicate. Log at {@code debug} and absorb. This
     *       is normal traffic under at-least-once delivery, not an incident, and logging it louder would train
     *       operators to ignore the log.</li>
     *   <li><b>Anything else</b> — genuinely inconsistent: a reply that does not fit the machine at all, such
     *       as a payment settling for an order already cancelled. Log at {@code error} and absorb.</li>
     * </ul>
     *
     * <p><b>Why anomalies are absorbed rather than thrown.</b> Throwing returns the message to the broker,
     * which redelivers it, and since the state will not have changed the same anomaly recurs forever — a poison
     * message consuming its partition. A message that cannot be processed must leave the queue. In Slice 4 it
     * leaves via a dead-letter topic where a human can inspect it; here it leaves via an error log, which is
     * the honest Slice 2 equivalent and is called out as a gap rather than presented as sufficient.
     *
     * <p>An unknown order id is treated the same way: it means the reply is for an order this context never
     * created, or one deleted beneath us. Not recoverable by retrying.
     */
    private Optional<Order> loadForTransition(UUID orderId,
                                              OrderStatus required,
                                              Set<OrderStatus> alreadyApplied,
                                              String replyType) {

        Optional<Order> found = loadOrderPort.loadById(orderId);
        if (found.isEmpty()) {
            log.error("{} received for unknown order {}: absorbing (not retriable)", replyType, orderId);
            return Optional.empty();
        }

        Order order = found.get();
        OrderStatus current = order.orderStatus();

        if (current == required) {
            return found;
        }

        if (alreadyApplied.contains(current)) {
            log.debug("Duplicate {} for order already in {}: absorbing", replyType, current);
        } else {
            log.error("Anomalous {} for order in state {} (expected {}): absorbing without action",
                    replyType, current, required);
        }
        return Optional.empty();

    }


    /**
     * Runs a handler with the saga and order ids bound to the logging context.
     *
     * <p>Every log line emitted inside — including from the domain and the adapters, since MDC is
     * thread-local — carries both ids, so one saga's journey across five handlers and three modules can be
     * filtered as a unit without correlating timestamps by hand. This is the cheapest observability available
     * in a distributed flow and the first thing missing when a saga misbehaves in production.
     *
     * <p>The {@code finally} is not optional: these threads are pooled (and virtual, here), so a key left
     * behind would attach a stale saga id to some unrelated request's logs.
     */
    private void withSagaContext(UUID sagaId, UUID orderId, Runnable handler) {
        MDC.put(MDC_SAGA_ID, sagaId.toString());
        MDC.put(MDC_ORDER_ID, orderId.toString());
        try {
            handler.run();
        } finally {
            MDC.remove(MDC_SAGA_ID);
            MDC.remove(MDC_ORDER_ID);
        }
    }

    /**
     * Builds the process payment command from the created event.
     */
    private ProcessPayment toProcessPayment(UUID sagaId, OrderCreatedEvent event) {
        return new ProcessPayment(
                sagaId,
                event.orderId(),
                event.customerId(),
                event.price().amount(),
                event.price().currency(),
                now());
    }

    /**
     * Builds the refund payment command from the persisted order.
     */
    private RefundPayment toRefundPayment(UUID sagaId, Order order) {
        return new RefundPayment(
                sagaId,
                order.orderId(),
                order.customerId(),
                order.price().amount(),
                order.price().currency(),
                now());
    }

    /**
     * Builds the pivot command from the persisted order.
     *
     * <p>Lines carry product ids and quantities only. The kitchen needs to know what to cook, not what it cost
     * — the same boundary discipline that keeps money out of the inbound command, applied outbound.
     */
    private ApproveOrder toApproveOrder(UUID sagaId, Order order) {
        List<ApproveOrder.Line> lines = order.items().stream()
                .map(item -> new ApproveOrder.Line(item.productId(), item.quantity()))
                .toList();

        return new ApproveOrder(sagaId, order.orderId(), order.restaurantId(), lines, now());
    }

    private Instant now() {
        return clock.instant();
    }
}
