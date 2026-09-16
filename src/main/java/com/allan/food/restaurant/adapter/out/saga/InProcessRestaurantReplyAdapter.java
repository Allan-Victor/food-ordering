package com.allan.food.restaurant.adapter.out.saga;

import com.allan.food.restaurant.application.port.out.RestaurantReplyPort;
import com.allan.food.saga.contract.SagaContract;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import static com.allan.food.saga.contract.SagaContract.*;

/**
 * Slice 2 transport for the pivot's outcome.
 *
 * <p>The deferral matters more here than anywhere else in the system: an {@code OrderApproved} escaping before
 * its transaction committed would tell the saga the pivot had passed when it had not, and the saga would run
 * forward past the last point at which it could recover.
 */
@Component("restaurantReplyAdapter")
class InProcessRestaurantReplyAdapter implements RestaurantReplyPort {

    private final ApplicationEventPublisher publisher;

    InProcessRestaurantReplyAdapter(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void approved(OrderApproved reply) {
        publisher.publishEvent(reply);
    }

    @Override
    public void rejected(OrderRejected reply) {
        publisher.publishEvent(reply);
    }
}
