package com.allan.food.restaurant.application.port.in;

import com.allan.food.saga.contract.SagaContract;

import static com.allan.food.saga.contract.SagaContract.*;

/**
 * Driving port through which the pivot command enters the restaurant context.
 *
 * <p><b>One method, and the absence of a second is the design.</b> {@code PaymentCommandListener} has two —
 * do, and undo. There is no {@code onUnapproveOrder} here because approval cannot be undone: once the kitchen
 * starts, no message reverses it. The interface is the pivot classification made checkable by the compiler,
 * and anyone who later adds a compensation method to it has changed the saga's recovery model whether they
 * meant to or not.
 *
 * <p>Implementations must be idempotent in the participant sense established on
 * {@code PaymentCommandListener}: recognise repeated work <i>and re-send the original reply</i>, since a
 * duplicated command most often means the first reply was lost.
 */
public interface RestaurantCommandListener {

    /** Decides whether the restaurant will prepare this order, and replies either way. */
    void onApproveOrder(ApproveOrder command);
}
