package com.allan.food.order.application.port.in.command;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Input to {@link TrackOrderUseCase#trackOrder}: the tracking id to look up.
 *
 * <p><b>Decision:</b> a raw {@code UUID}, not a wrapped {@code TrackingId}, per our project-wide unwrapped-
 * identity choice. Named {@code trackingId}, not the reference's {@code orderTrackingId} — the {@code order}
 * prefix is redundant inside an order-context type.
 *
 * <p><b>Alternative you'll see elsewhere:</b> the reference wraps this in a {@code TrackingId} value object —
 * compile-time protection against mixing id kinds, at the cost of a mapper hop at every boundary.
 */
public record TrackOrderQuery(@NotNull UUID trackingId) {
}
