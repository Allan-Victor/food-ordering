package com.allan.food.order.domain.event;

import java.time.Instant;

/**
 * A fact: something that has already happened in the domain.
 * <p>
 * Domain Events are named in the past tense and are immutable - they
 * record history and history does not change. They are raised by the
 * domain and published by the application layer; the domain itself must
 * never know that a message broker exists.
 *
 * <p><strong>Design note - payload shape. </strong> Implementations carry
 * identifiers and the minimum data a consumer needs, never the aggregate
 * itself. Carrying the aggregate is convenient in-process but couples the
 * event's wire format to the internal model, so every refractor of the
 * aggregate becomes a breaking change for every consumer. keeping events
 * flat means they serialise directly to Kafka with no
 * translation layer.
 *
 * <p>{@code sealed} so that consumers can switch exhaustively over the
 * known event types, and the compiler flags a missing branch when a new
 * one is added.
 */
public sealed interface DomainEvent permits OrderCreatedEvent, OrderPaidEvent, OrderCancelledEvent {

    /**
     * When the fact occurred.
     *
     * <p>{@link Instant} rather than {@code ZonedDateTime}: an instant is
     * by definition a point on the UTC timeline, which is exactly what a
     * machine-generated timestamp is. {@code ZonedDateTime} is for
     * human-facing local time with daylight-saving rules, which an  event
     * timestamp is not.
     */
    Instant occurredAt();
}
