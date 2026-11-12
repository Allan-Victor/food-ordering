package com.allan.food.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;
import java.util.UUID;

/**
 * The order aggregate's own side effects, pulled out of workflow code and into an activity.
 *
 * <p>See {@code OrderSagaWorkflow}'s package Javadoc for why this contract lives in {@code temporal} rather
 * than {@code order} itself. Its implementation lives in {@code order.application.saga} — the same package
 * {@code OrderSaga} occupies on {@code main} — wrapping {@code loadOrderPort}/{@code saveOrderPort} and
 * {@code Order}'s existing {@code pay()}/{@code approve()}/{@code initCancel()}/{@code cancel()} transition
 * methods unchanged.
 *
 * <h2>Why this exists as a separate activity at all</h2>
 *
 * On {@code main}, {@code OrderSaga} calls {@code saveOrderPort.save(order)} inline — already inside a
 * Spring-managed transaction, nothing wrong with that there. Workflow code is different: it must be
 * deterministic and replay-safe, which rules out any direct I/O, database writes included. A database write is
 * exactly the kind of side effect Temporal requires to live behind an activity boundary, the same as a call to
 * an external payment gateway.
 */

@ActivityInterface
public interface OrderStateActivities {

    @ActivityMethod
    void markPaid(UUID orderId);

    @ActivityMethod
    void markApproved(UUID orderId);

    @ActivityMethod
    void markCancelling(UUID orderId, List<String> reasons);

    @ActivityMethod
    void markCancelled(UUID orderId, List<String> reasons);
}
