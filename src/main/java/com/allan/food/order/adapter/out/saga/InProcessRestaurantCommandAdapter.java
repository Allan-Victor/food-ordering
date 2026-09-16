package com.allan.food.order.adapter.out.saga;

import com.allan.food.order.application.port.out.RestaurantCommandPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import static com.allan.food.saga.contract.SagaContract.*;

/**
 * Slice 2 transport for the pivot command. Same mechanism and same caveats as
 * {@link InProcessPaymentCommandAdapter}; one method, because a pivot has no compensation to dispatch.
 */
@Component("orderRestaurantCommandAdapter")
class InProcessRestaurantCommandAdapter implements RestaurantCommandPort {

    private static final Logger log = LoggerFactory.getLogger(InProcessRestaurantCommandAdapter.class);

    private final ApplicationEventPublisher publisher;

    InProcessRestaurantCommandAdapter(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void approve(ApproveOrder command) {
        log.debug("Dispatching ApproveOrder for order {} (pivot)", command.orderId());
        publisher.publishEvent(command);
    }
}
