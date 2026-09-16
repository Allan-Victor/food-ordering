package com.allan.food.restaurant.adapter.in.saga;

import com.allan.food.restaurant.application.port.in.RestaurantCommandListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import static com.allan.food.saga.contract.SagaContract.*;

/**
 * Driving adapter delivering the pivot command into the restaurant context.
 *
 * <p>Same shape and same {@code REQUIRES_NEW} reasoning as the payment context's inbound adapter. One method,
 * because the pivot has no compensation to receive.
 */
@Component("restaurantCommandAdapter")
class InProcessRestaurantCommandAdapter {

    private final RestaurantCommandListener listener;

    InProcessRestaurantCommandAdapter(RestaurantCommandListener listener) {
        this.listener = listener;
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ApproveOrder command) {
        listener.onApproveOrder(command);
    }
}