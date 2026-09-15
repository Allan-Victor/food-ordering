package com.allan.food.restaurant.application.port.out;

import com.allan.food.saga.contract.SagaContract;

import static com.allan.food.saga.contract.SagaContract.*;

/**
 * Driven port for sending the pivot's outcome back to the saga orchestrator.
 *
 * <p>Two methods for the two sides of the pivot: {@link #approved} commits the saga to completion,
 * {@link #rejected} triggers its only backward-recovery path.
 *
 * <p>Called inside the handler's transaction with delivery deferred past commit by the adapter — the contract
 * established on {@code PublishEventPort}. It matters more here than anywhere else in the system: an
 * {@code OrderApproved} that escaped before its transaction rolled back would tell the saga the pivot had
 * committed when it had not, and the saga would run forward past the last point at which it could have
 * recovered.
 */
public interface RestaurantReplyPort {

    void approved(OrderApproved reply);

    void rejected(OrderRejected reply);
}
