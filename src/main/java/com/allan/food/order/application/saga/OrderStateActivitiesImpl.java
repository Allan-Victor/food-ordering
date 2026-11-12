package com.allan.food.order.application.saga;

import com.allan.food.order.application.port.out.LoadOrderPort;
import com.allan.food.order.application.port.out.SaveOrderPort;
import com.allan.food.order.domain.model.entity.Order;
import com.allan.food.order.domain.model.valueobject.OrderStatus;
import com.allan.food.temporal.OrderStateActivities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static com.allan.food.order.domain.model.valueobject.OrderStatus.*;

/**
 * Implements {@link OrderStateActivities} by wrapping {@link LoadOrderPort}/{@link SaveOrderPort} and
 * {@code Order}'s own transition methods — unchanged from {@code main}.
 *
 * <h2>Same idempotency discipline as {@code OrderSaga}, adapted to a different shape</h2>
 *
 * Activities are retried under Temporal exactly as replies are redelivered under a message broker: at least
 * once, sometimes more. The guard here is {@code OrderSaga.loadForTransition} generalised from one required
 * state to a set of them — needed because {@link #markCancelled} now serves two of {@code OrderSaga}'s five
 * reply handlers ({@code onPaymentFailed} and {@code onPaymentRefunded}), which shared one aggregate method,
 * {@code Order.cancel()}, but started from different states. Everywhere else the set is a singleton and the
 * check behaves exactly as it does on {@code main}.
 *
 * <h2>Why {@code @Transactional} without {@code REQUIRES_NEW}</h2>
 *
 * The {@code REQUIRES_NEW} rule (documented in {@code CLAUDE.md} and on every {@code AFTER_COMMIT} listener in
 * this codebase) exists because {@code @TransactionalEventListener(AFTER_COMMIT)} runs after the publishing
 * transaction has already ended, with nothing ambient to join. Nothing here fires that way: the Temporal
 * worker calls these methods directly, so plain {@code @Transactional} opens the transaction this method needs
 * on its own, the same as any ordinary service entry point.
 *
 * <h2>What the caller still owns</h2>
 *
 * {@link #markCancelled} covers both {@code onPaymentFailed}'s outright cancellation and
 * {@code onPaymentRefunded}'s lock release, matching {@code Order.cancel()}'s own dual precondition
 * ({@code PENDING} or {@code CANCELLING}). Which one a given call is, and therefore what {@code reasons} should
 * contain, is the workflow's decision, not this activity's: pass the real failure reasons when cancelling
 * outright after a declined payment, and an empty list when releasing the lock after a settled refund — the
 * same distinction {@code onPaymentFailed} and {@code onPaymentRefunded} encode today by being two methods
 * instead of one.
 */
@Component
class OrderStateActivitiesImpl implements OrderStateActivities {

    private static final Logger log = LoggerFactory.getLogger(OrderStateActivitiesImpl.class);

    private final LoadOrderPort loadOrderPort;
    private final SaveOrderPort saveOrderPort;

    OrderStateActivitiesImpl(LoadOrderPort loadOrderPort, SaveOrderPort saveOrderPort) {
        this.loadOrderPort = loadOrderPort;
        this.saveOrderPort = saveOrderPort;
    }

    @Override
    @Transactional
    public void markPaid(UUID orderId) {
        applyTransition(orderId, Set.of(PENDING), Set.of(PAID, APPROVED), "markPaid", Order::pay);
    }

    @Override
    @Transactional
    public void markApproved(UUID orderId) {
        applyTransition(orderId, Set.of(PAID), Set.of(APPROVED), "markApproved", Order::approve);
    }

    @Override
    @Transactional
    public void markCancelling(UUID orderId, List<String> reasons) {
        applyTransition(orderId, Set.of(PAID), Set.of(CANCELLING, CANCELLED), "markCancelling",
                order -> order.initCancel(reasons));
    }

    @Override
    @Transactional
    public void markCancelled(UUID orderId, List<String> reasons) {
        applyTransition(orderId, Set.of(PENDING, CANCELLING), Set.of(CANCELLED), "markCancelled",
                order -> order.cancel(reasons));

    }

    /**
     * The activity-shaped equivalent of {@code OrderSaga.loadForTransition} + {@code handleReply}, collapsed
     * into one method since there's no separate reply event or MDC-bearing saga id to bind here — Temporal's
     * own worker/activity execution context carries that job instead.
     */
    private void applyTransition(UUID orderId,
                                 Set<OrderStatus> requiredStates,
                                 Set<OrderStatus> alreadyApplied,
                                 String activityName,
                                 Consumer<Order> transition) {

        Optional<Order> found = loadOrderPort.loadById(orderId);
        if (found.isEmpty()) {
            log.error("{} received for unknown order {}: absorbing (not retriable)", activityName, orderId);
            return;
        }

        Order order = found.get();
        OrderStatus current = order.orderStatus();

        if (requiredStates.contains(current)) {
            transition.accept(order);
            saveOrderPort.save(order);
            return;
        }

        if (alreadyApplied.contains(current)) {
            log.debug("Duplicate {} for order already in {}: absorbing", activityName, current);
        } else {
            log.error("Anomalous {} for order in state {} (expected one of {}): absorbing without action",
                    activityName, current, requiredStates);
        }
    }
}
