package com.allan.food.order.adapter.in.web;

import com.allan.food.order.application.port.in.command.TrackOrderResponse;

import java.util.List;
import java.util.UUID;

/**
 * The two response bodies this adapter serves, grouped in one file because each is a two-line record and
 * separate files would be filing, not organisation.
 *
 * <p>A namespace, not a type — see {@link #OrderResponses()}.
 */
final class OrderResponses {

    private OrderResponses() {
        throw new AssertionError("No instances");
    }

    /**
     * Body of a successful {@code POST /api/v1/orders}.
     *
     * <p><b>Only the tracking id, and no status field.</b> A freshly created order is invariantly
     * {@code PENDING}; returning it would suggest a value the client should inspect when there is nothing to
     * learn. The reference returns {@code {trackingId, status, message}} — the {@code message} being
     * human-readable prose in a machine-readable body, which clients then parse or ignore.
     *
     * <p>The status the client actually wants comes from following the {@code Location} header this response is
     * sent with.
     */
    record OrderCreated(UUID trackingId) {
    }

    /**
     * Body of {@code GET /api/v1/orders/{trackingId}}.
     *
     * <p><b>The status is a {@code String}, not the {@code OrderStatus} enum, and that is the point of the
     * class.</b> Serialising the enum directly makes every constant name part of the published API contract —
     * rename {@code CANCELLING} in the domain and every client breaks, with no compiler anywhere noticing.
     * Converting here means a domain rename is caught at this line and a deliberate decision about the wire
     * format gets made. That single field is what earns this record its existence.
     */
    record OrderTracking(UUID trackingId, String status, List<String> failureMessages) {

        /** Translates the application-layer result into the wire shape. */
        static OrderTracking from(TrackOrderResponse response) {
            return new OrderTracking(
                    response.trackingId(),
                    response.status().name(),
                    response.failureMessages());
        }
    }
}
