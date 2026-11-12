package com.allan.food.temporal;

import com.allan.food.saga.contract.SagaContract;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The order saga orchestrator, Temporal edition.
 *
 * <h2>Where this lives, and why</h2>
 *
 * This package sits beside {@code saga.contract}, not inside {@code order} — it plays the same role for this
 * branch's transport that {@code saga.contract} plays for the message-based one: shared saga vocabulary, no
 * business logic of its own. The activity interfaces declared alongside it ({@code OrderStateActivities},
 * {@code PaymentActivities}, {@code RestaurantActivities}) follow the same rule. Their implementations do
 * <i>not</i> live here; each stays inside its owning context's adapter package — {@code PaymentActivities} is
 * implemented in {@code payment.adapter.in.saga}, the exact package {@code InProcessPaymentCommandAdapter}
 * occupies on {@code main} — the same contract-here, implementation-there split {@code PaymentCommandPort} and
 * its adapter already demonstrate. That split is what lets {@code PaymentService} stay package-private inside
 * {@code payment} while this package still declares the shape payment must fulfil.
 *
 * <h2>What moves, what doesn't</h2>
 *
 * The participants — {@code PaymentService}, {@code RestaurantApprovalService}, {@code Order}'s own transition
 * methods — are unchanged. Only the orchestration and delivery layer changes: a durable, replay-driven
 * function replaces a chain of {@code @TransactionalEventListener} handlers reacting to persisted events.
 *
 * <h2>Why {@code run} returns {@link Outcome}</h2>
 *
 * Not {@code void}: the mainline saga is fire-and-forget by construction — every handler commits and returns
 * with no caller waiting. Temporal has no such constraint; a caller can await workflow completion and get a
 * real terminal answer, which costs nothing to provide.
 *
 * <p>Not {@code order}'s own {@code OrderStatus}: reusing it here would give this package a dependency on
 * {@code order}'s domain model, which {@code saga.contract} deliberately does not have — its records carry
 * only JDK types. {@link Outcome} keeps that same leaf-dependency posture; the workflow implementation maps
 * the aggregate's real, richer {@code OrderStatus} down to this narrower result at the boundary, the same
 * direction {@code OrderPersistenceMapper} already maps a persistence shape down to a domain one.
 */

@WorkflowInterface
public interface OrderSagaWorkflow {

    @WorkflowMethod
    Outcome run(Input input);

    /**
     * Everything the workflow needs to start a saga run. Deliberately narrower than a full {@code Order} — this
     * is what starts the process, not a copy of the aggregate; the workflow reaches the rest through
     * {@link OrderStateActivities}.
     */
    record Input(UUID orderId, UUID customerId, BigDecimal amount, String currency,
                 UUID restaurantId, List<SagaContract.ApproveOrder.Line> lines) {}

    /**
     * The saga's terminal result — the same two states {@code OrderStatus} settles into on {@code main}
     * ({@code APPROVED} via the happy path, {@code CANCELLED} via either early payment failure or the
     * reject-then-refund compensation path), narrowed to what a caller waiting on this workflow actually
     * needs to know.
     */
    enum Outcome { APPROVED, CANCELLED }
}
