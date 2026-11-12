package com.allan.food.restaurant.adapter.in.saga;

import com.allan.food.restaurant.application.port.out.RestaurantReplyPort;
import com.allan.food.saga.contract.SagaContract.OrderApproved;
import com.allan.food.saga.contract.SagaContract.OrderRejected;
import com.allan.food.temporal.RestaurantActivities.ApprovalResult;
import org.springframework.stereotype.Component;

/**
 * Stands in for {@code InProcessRestaurantReplyAdapter} — the event-publishing {@code RestaurantReplyPort}
 * adapter {@code main} wires — capturing {@code RestaurantApprovalService}'s reply on the calling thread instead
 * of publishing it. Same reasoning as {@code SynchronousPaymentReplyPort}: the service depends on the port
 * interface, never a concrete adapter, so swapping which one satisfies it costs nothing; thread-confined for
 * the same reason too — one activity task, one worker thread, nothing interleaved on it.
 */
@Component
class SynchronousRestaurantReplyPort implements RestaurantReplyPort {

    private final ThreadLocal<ApprovalResult> result = new ThreadLocal<>();

    @Override
    public void approved(OrderApproved reply) {
        result.set(new ApprovalResult.Approved());
    }

    @Override
    public void rejected(OrderRejected reply) {
        result.set(new ApprovalResult.Rejected(reply.reasons()));
    }

    ApprovalResult takeResult() {
        ApprovalResult captured = result.get();
        result.remove();
        return captured;
    }
}
