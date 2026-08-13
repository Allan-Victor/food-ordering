package com.allan.food.order.application.port.out;

import com.allan.food.order.domain.event.DomainEvent;

/**
 * Driven port for emitting domain events produced by the aggregate.
 *
 * <p><b>Semantics — this records that something happened; it does not promise immediate delivery.</b> The
 * application calls this <i>inside</i> the transaction, right after saving the aggregate. When the event
 * actually reaches a consumer is the adapter's decision, and that is deliberately where the hard part lives:
 * <ul>
 *   <li><b>Slice 1</b> — the adapter delegates to Spring's {@code ApplicationEventPublisher}; nothing consumes
 *       the event yet, but the seam is real and wired.</li>
 *   <li><b>Slice 2</b> — in-process listeners annotate {@code @TransactionalEventListener(phase = AFTER_COMMIT)},
 *       so side effects cannot fire for a transaction that rolls back.</li>
 *   <li><b>Slice 3</b> — the adapter writes an outbox row in the <i>same</i> transaction and a relay publishes
 *       it, making persistence and emission genuinely atomic.</li>
 * </ul>
 * None of that changes this port or its callers. That containment is the whole reason the dependency is stated
 * as a port rather than reached for directly.
 *
 * <p><b>Alternative you'll see elsewhere — and why we rejected it:</b> the reference declares a generic
 * {@code DomainEventPublisher<T extends DomainEvent>} plus one marker interface per event and destination
 * ({@code OrderCreatedPaymentRequestMessagePublisher}, {@code OrderPaidRestaurantRequestMessagePublisher},
 * {@code OrderCancelledPaymentRequestMessagePublisher}). That bakes <i>routing</i> — which broker topic an event
 * goes to — into the port's type structure, so every new destination adds an interface. Because our
 * {@code DomainEvent} is a <b>sealed</b> interface, one port suffices: the adapter routes with an exhaustive
 * {@code switch} pattern match, the compiler proves no event kind is unhandled, and routing stays an
 * infrastructure detail where it belongs.
 *
 * <p><b>On the dual-write hazard:</b> publishing before commit risks announcing an order that rolls back;
 * publishing after commit risks losing the event if the process dies in between. The reference restructures its
 * classes to publish after commit, accepting the second risk. We keep the call inside the transaction and let
 * the adapter close the gap — properly, at Slice 3.
 */
public interface PublishEventPort {
    /**
     * Records a domain event for emission
     *
     * @param event the event produced by the aggregate; never {@code null}
     */
    void publish(DomainEvent event);
}
