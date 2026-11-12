package com.allan.food.restaurant.adapter.in.saga;

import com.allan.food.restaurant.application.port.in.RestaurantCommandListener;
import com.allan.food.saga.contract.SagaContract.ApproveOrder;
import com.allan.food.temporal.RestaurantActivities;
import io.temporal.activity.Activity;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * Implements {@link RestaurantActivities} by driving the existing {@link RestaurantCommandListener} —
 * {@code RestaurantApprovalService}, unchanged — through {@link SynchronousRestaurantReplyPort}.
 *
 * <p>Same shape as {@code PaymentActivitiesImpl}: {@code ApproveOrderRequest} omits {@code sagaId}/
 * {@code issuedAt} for the reasons given on {@code RestaurantActivities}'s Javadoc; both are synthesised here,
 * with {@code sagaId} taken from the workflow's own id via {@code Activity.getExecutionContext()}; and no
 * {@code @Transactional} here since {@code onApproveOrder} is already transactional and unchanged.
 */
@Component
class RestaurantActivitiesImpl implements RestaurantActivities {

    private final RestaurantCommandListener listener;
    private final SynchronousRestaurantReplyPort replyCapture;
    private final Clock clock;

    RestaurantActivitiesImpl(RestaurantCommandListener listener, SynchronousRestaurantReplyPort replyCapture,
                             Clock clock) {
        this.listener = listener;
        this.replyCapture = replyCapture;
        this.clock = clock;
    }

    @Override
    public ApprovalResult approveOrder(ApproveOrderRequest request) {
        listener.onApproveOrder(new ApproveOrder(
                correlationId(), request.orderId(), request.restaurantId(),
                request.lines(), clock.instant()));
        return replyCapture.takeResult();
    }

    private UUID correlationId() {
        return UUID.fromString(Activity.getExecutionContext().getInfo().getWorkflowId());
    }
}
