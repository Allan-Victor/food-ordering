package com.allan.food.order.application.port.out;

import com.allan.food.saga.contract.SagaContract;

/**
 * Driven port for issuing the pivot command to the restaurant participant.
 *
 * <p>A single method, because the pivot has no compensation to request. That asymmetry with
 * {@link PaymentCommandPort} is the taxonomy showing through in the type system: a compensatable step's port
 * needs a way to undo, a pivot's does not, because undoing is precisely what is unavailable past this point.
 */
public interface RestaurantCommandPort {

    /** Asks the restaurant to accept the order and begin preparation. */
    void approve(SagaContract.ApproveOrder command);
}
