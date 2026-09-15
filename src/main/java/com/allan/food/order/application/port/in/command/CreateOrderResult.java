package com.allan.food.order.application.port.in.command;

import java.util.UUID;

/**
 * The outcome of a successful {@link CreateOrderUseCase#createOrder}: the tracking id the caller polls
 * with afterwards.
 *
 * <p><b>Decision:</b> a one-field {@code record}, not a bare {@code UUID}. The domain holds four distinct
 * UUIDs (order, tracking, customer, restaurant); a named return type says <i>which</i> at the type level
 * and keeps the signature stable if the result grows. Status is intentionally omitted — at creation it is
 * invariantly {@code PENDING}, so returning it would falsely imply variance (unlike {@link TrackOrderResponse}).
 *
 * <p><b>Alternative you'll see elsewhere:</b> the reference returns {@code CreateOrderResponse(trackingId,
 * status, message)}. The {@code message} is presentation, belonging in the web adapter's response DTO, not
 * the application contract.
 */
public record CreateOrderResult(UUID trackingId) {
}
