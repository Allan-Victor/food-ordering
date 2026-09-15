package com.allan.food.order.application.port.in;

import com.allan.food.saga.contract.SagaContract.OrderApproved;
import com.allan.food.saga.contract.SagaContract.OrderRejected;

/**
 * Driving port through which restaurant replies enter the application and resolve the saga.
 *
 * <p>The two methods are the two sides of the pivot. {@link #onOrderApproved} commits the saga to completion;
 * {@link #onOrderRejected} is the last opportunity for backward recovery in the entire process. Everything the
 * taxonomy says about pivots is visible in this one pair of signatures.
 *
 * <p>Idempotency applies exactly as it does on {@link PaymentReplyListener}.
 */
public interface RestaurantReplyListener {

    /** The restaurant accepted: {@code PAID → APPROVED}, terminal, saga complete. */
    void onOrderApproved(OrderApproved reply);

    /** The restaurant refused: {@code PAID → CANCELLING}, then compensate the payment. */
    void onOrderRejected(OrderRejected reply);
}
