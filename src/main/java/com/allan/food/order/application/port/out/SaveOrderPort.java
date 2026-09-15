package com.allan.food.order.application.port.out;

import com.allan.food.order.domain.model.entity.Order;

/**
 * Driven port for persisting an {@link Order} aggregate.
 *
 * <p><b>Decision — fine-grained ports, split by the reason to depend.</b> Persisting and loading are two
 * separate ports ({@link LoadOrderPort} is the read side) so each application service depends only on the
 * capability it actually uses: the create use case saves, the track use case loads, neither drags in the
 * other. This mirrors the driving side, where we also split {@code CreateOrderUseCase}/{@code TrackOrderUseCase}.
 *
 * <p><b>Alternative you'll see elsewhere:</b> DDD's classic "one Repository per aggregate" bundles save and
 * find into a single {@code OrderRepository} (the reference's shape). Fewer types, but the create service
 * then carries a transitive dependency on a {@code find} it never calls.
 *
 * <p>Public by necessity: this is the deliberate outbound contract of the hexagon, implemented by the
 * persistence adapter in another package.
 */
public interface SaveOrderPort {

    /**
     * Persists the given order and returns the stored aggregate.
     *
     * <p><b>Why return {@code Order} rather than {@code void}:</b> matches the recognisable Spring Data
     * {@code save} idiom and lets the adapter hand adapter-managed fields back to the domain — notably the
     * optimistic-lock {@code @Version} arriving in Slice 2. In Slice 1 the returned aggregate is logically
     * identical to the argument (our id is domain-generated, not database-generated).
     *
     * @param order a fully-valid aggregate to persist
     * @return the persisted aggregate
     */
    Order save(Order order);
}
