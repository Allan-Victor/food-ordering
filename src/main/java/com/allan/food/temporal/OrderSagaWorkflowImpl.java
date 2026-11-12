package com.allan.food.temporal;

import com.allan.food.temporal.PaymentActivities.PaymentResult;
import com.allan.food.temporal.RestaurantActivities.ApprovalResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.List;

/**
 * Implements {@link OrderSagaWorkflow} — occupies the role {@code OrderSaga} holds on {@code main}: the one
 * place the saga's full shape is readable start to finish.
 *
 * <h2>Lives in {@code temporal}, not {@code order}</h2>
 *
 * Unlike the three {@code *ActivitiesImpl} classes, this class has no participant-specific business logic of
 * its own to delegate to — it only sequences calls across {@link OrderStateActivities}, {@link PaymentActivities}
 * and {@link RestaurantActivities}, none of which {@code order} exposes outward. Placing it in {@code order}
 * would force {@code order} to reach into {@code temporal}'s internals for all three contracts while
 * {@code temporal} reached back into {@code order} to register this very class with the worker — a dependency
 * cycle. Keeping it here, beside the contracts it sequences, is what breaks that cycle; nothing about the
 * saga's shape changes by moving it.
 *
 * <h2>A plain POJO, not a Spring bean — unlike every {@code *ActivitiesImpl}</h2>
 *
 * The three activity implementations are {@code @Component}s, constructor-injected. This class is neither:
 * Temporal instantiates workflow implementations itself, fresh per execution, via a public no-arg constructor,
 * because workflow state lives in Temporal's replay mechanism, not in a long-lived Java object the way a
 * singleton service's state would. {@link Workflow#newActivityStub} below isn't dependency injection — it
 * builds a dynamic proxy Temporal manages internally, unrelated to Spring's container.
 *
 * <h2>No {@code io.temporal.workflow.Saga} helper here — deliberately</h2>
 *
 * Temporal ships a generic {@code Saga} class for registering and LIFO-unwinding an arbitrary sequence of
 * compensations. This saga was never that shape: {@code OrderSaga}'s own locked design is that compensation is
 * directional, not stack-based — exactly one path backward, rejection triggers a refund, true regardless of
 * how far forward the saga had gotten. Reaching for generic multi-step unwind machinery to model one fixed,
 * always-the-same backward path would be solving a more general problem than the one that exists — the same
 * over-engineering this project already declined when it chose the hand-rolled orchestrator over a generic
 * saga framework for {@code main} in the first place. The plain branch below is the honest shape of what this
 * saga actually does.
 *
 * <h2>Why retrying activities needs nothing new here</h2>
 *
 * Temporal retries an activity automatically on an unhandled technical fault. That's only safe because
 * {@code PaymentService}/{@code RestaurantApprovalService} were already required to be idempotent for the
 * message-redelivery world {@code main} lives in — the same guard that answers a duplicate {@code ProcessPayment}
 * correctly also answers a Temporal-initiated retry correctly, with nothing added on this branch to make it so.
 *
 * <h2>Reading the flow against {@code OrderSaga}</h2>
 *
 * {@link #run} is {@code onOrderCreated}'s trigger; a declined payment is {@code onPaymentFailed}; a completed
 * payment falling through to {@link #pivot} is what {@code onPaymentCompleted} sets up; an approval is
 * {@code onOrderApproved}; a rejection running {@code markCancelling} → {@code refundPayment} →
 * {@code markCancelled} is {@code onOrderRejected} followed by {@code onPaymentRefunded} — the saga's only
 * backward path, unchanged in shape, just no longer split across two separately-triggered reply handlers.
 */
public class OrderSagaWorkflowImpl implements OrderSagaWorkflow {

    private static final ActivityOptions ACTIVITY_OPTIONS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .build();

    private final OrderStateActivities orderState = Workflow.newActivityStub(OrderStateActivities.class, ACTIVITY_OPTIONS);
    private final PaymentActivities payment = Workflow.newActivityStub(PaymentActivities.class, ACTIVITY_OPTIONS);
    private final RestaurantActivities restaurant = Workflow.newActivityStub(RestaurantActivities.class, ACTIVITY_OPTIONS);

    @Override
    public Outcome run(Input input) {
        PaymentResult paymentResult = payment.processPayment(new PaymentActivities.ProcessPaymentRequest(
                input.orderId(), input.customerId(), input.amount(), input.currency()));

        return switch (paymentResult) {
            case PaymentResult.Declined declined -> {
                orderState.markCancelled(input.orderId(), declined.reasons());
                yield Outcome.CANCELLED;
            }
            case PaymentResult.Completed ignored -> {
                orderState.markPaid(input.orderId());
                yield pivot(input);
            }
        };
    }

    /** The pivot: once the restaurant answers, the saga either completes or takes its one backward step. */
    private Outcome pivot(Input input) {

        ApprovalResult approvalResult = restaurant.approveOrder(new RestaurantActivities.ApproveOrderRequest(
                input.orderId(), input.restaurantId(), input.lines()));

        return switch (approvalResult) {
            case ApprovalResult.Approved ignored -> {
                orderState.markApproved(input.orderId());
                yield Outcome.APPROVED;
            }
            case ApprovalResult.Rejected rejected -> {
                orderState.markCancelling(input.orderId(), rejected.reasons());
                payment.refundPayment(new PaymentActivities.RefundPaymentRequest(
                        input.orderId(), input.customerId(), input.amount(), input.currency()));
                orderState.markCancelled(input.orderId(), List.of());
                yield Outcome.CANCELLED;
            }
        };
    }
}
