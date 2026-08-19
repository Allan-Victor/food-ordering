package com.allan.food.order.adapter.out.event;

import com.allan.food.order.application.port.out.PublishEventPort;
import com.allan.food.order.domain.event.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Slice 1 implementation of {@link PublishEventPort}: hands events to Spring's in-process event multicaster.
 *
 * <p><b>Read the name as a statement of scope.</b> This adapter publishes <i>within the JVM</i> and offers no
 * durability guarantee — if the process dies after commit, the event is gone. That is acceptable now precisely
 * because nothing consumes these events yet. It stops being acceptable at Slice 3, when the sibling
 * {@code OutboxEventPublishingAdapter} replaces it: same port, same callers, durable emission. The application
 * service is untouched by that swap, which is the return on having declared the dependency as a port.
 *
 * <p><b>Why this is safe despite being called inside the transaction:</b> Spring's multicaster does not deliver
 * to {@code @TransactionalEventListener} subscribers until the configured transaction phase (default
 * {@code AFTER_COMMIT}). So a rolled-back order cannot trigger side effects, even though {@code publish} was
 * called before the commit. That property is what let us drop the reference's {@code OrderCreateHelper}
 * indirection entirely.
 *
 * <p><b>Alternative you'll see elsewhere — and why we rejected it:</b> the reference implements
 * {@code ApplicationEventPublisherAware} and receives the publisher through a lifecycle callback. That is the
 * pre-4.x Spring idiom; it forces a non-final field that is briefly {@code null} after construction and ties
 * the class to a Spring callback interface. {@code ApplicationEventPublisher} has been directly injectable for
 * years — constructor injection gives a {@code final} field and an object that is fully formed the moment it
 * exists.
 */
@Component
class InProcessEventPublishingAdapter implements PublishEventPort {

    private static final Logger log = LoggerFactory.getLogger(InProcessEventPublishingAdapter.class);

    private final ApplicationEventPublisher applicationEventPublisher;

    InProcessEventPublishingAdapter(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void publish(DomainEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        applicationEventPublisher.publishEvent(event);
        log.debug("Published {} occurring at {}", event.getClass().getSimpleName(), event.occurredAt());
    }
}
