package com.allan.food.order.application.port.in.command;

import com.allan.food.order.domain.model.valueobject.OrderStatus;

import java.util.List;
import java.util.UUID;

/**
 * Result of tracking an order: its current status and any failure messages accumulated so far.
 *
 * <p><b>Decision:</b> {@code status} is returned because — unlike at creation — it genuinely varies, and it
 * exposes our own {@link OrderStatus} enum directly. That enum lives in <i>this</i> context's domain, so
 * returning it from this context's application layer is honest; the web adapter's response DTO may stringify
 * it for the wire. {@code failureMessages} is normalised to an unmodifiable, never-null list.
 *
 * <p><b>Alternative you'll see elsewhere:</b> the reference sources {@code OrderStatus} from a shared
 * {@code common-domain} reused across services — Evans' shared-kernel, which couples independent contexts.
 */
public record TrackOrderResponse(
        UUID trackingId,
        OrderStatus status,
        List<String> failureMessages
) {
    /** Unmodifiable, null-safe copy out. */
    public TrackOrderResponse {
        failureMessages = failureMessages == null ? List.of() : List.copyOf(failureMessages);
    }
}
