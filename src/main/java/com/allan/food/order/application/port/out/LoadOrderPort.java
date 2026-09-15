package com.allan.food.order.application.port.out;

import com.allan.food.order.domain.model.entity.Order;

import java.util.Optional;
import java.util.UUID;

/**
 * Driven port for reading an {@link Order} aggregate back from storage.
 *
 * <p><b>Decision — key on the tracking id, and expose only that.</b> Slice 1's only read path is "where is
 * my order?", driven by the customer-facing {@code trackingId}. Loading by the internal order id is
 * deliberately <i>absent</i>: it has no caller until the saga's payment/approval callbacks arrive in Slice 2,
 * and a port method with no caller is a smell in a reference. It joins here then, not speculatively now.
 *
 * <p><b>Alternative you'll see elsewhere:</b> the reference puts {@code save} and {@code findByTrackingId} on
 * one {@code OrderRepository} and wraps the key in a {@code TrackingId} value object; we split the port (see
 * {@link SaveOrderPort}) and use a raw {@code UUID}.
 */
public interface LoadOrderPort {

    /**
     * Looks up an order by its opaque, customer-facing tracking id.
     *
     * @param trackingId the tracking id minted on the aggregate at creation
     * @return the order if one exists with that tracking id, otherwise empty
     */
    Optional<Order> loadByTrackingId(UUID trackingId);

    /**
     * Loads an order by its internal identifier.
     *
     * <p>Added in Slice 2 for the saga, which correlates replies on {@code orderId} rather than on the
     * customer-facing tracking id. Two lookup methods on one port is not duplication: they answer different
     * questions asked by different callers, and the tracking id is deliberately opaque so that a participant
     * service never sees it.
     */
    Optional<Order> loadById(UUID orderId);
}
