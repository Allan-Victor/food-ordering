package com.allan.food.temporal;

import com.allan.food.saga.contract.SagaContract.ApproveOrder.Line;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;
import java.util.UUID;

/**
 * How the restaurant context receives the pivot command on this branch.
 *
 * <p>See {@code OrderSagaWorkflow}'s package Javadoc for why this contract lives in {@code temporal} rather
 * than {@code restaurant} itself. Its implementation lives in {@code restaurant.adapter.in.saga} — the same
 * package {@code InProcessRestaurantCommandAdapter} occupies on {@code main} — and calls
 * {@code RestaurantApprovalService} directly.
 *
 * <p>{@link Line} is reused as-is from {@code SagaContract}: pure line-item data with no envelope fields, so
 * redefining it here would be duplication with no gain in boundary clarity — the one deliberate exception to
 * this package's otherwise dependency-free posture.
 *
 * <p>Rejection returns {@link ApprovalResult.Rejected}, not a thrown failure, for the same reason payment
 * decline does. The workflow inspects the result and, on rejection, explicitly drives the refund — the same
 * shape as {@code onOrderRejected}'s explicit refund call in {@code OrderSaga} today.
 */
@ActivityInterface
public interface RestaurantActivities {

    @ActivityMethod
    ApprovalResult approveOrder(ApproveOrderRequest request);

    record ApproveOrderRequest(UUID orderId, UUID restaurantId, List<Line> lines) {}

    sealed interface ApprovalResult {
        record Approved() implements ApprovalResult {}
        record Rejected(List<String> reasons) implements ApprovalResult {}
    }
}
