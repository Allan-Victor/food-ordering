package com.allan.food.order.adapter.out.persistence;

import com.allan.food.order.application.port.out.LoadOrderPort;
import com.allan.food.order.application.port.out.SaveOrderPort;
import com.allan.food.order.domain.model.entity.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Driven adapter fulfilling the order-persistence ports with JPA.
 *
 * <p><b>One adapter, two ports — and that is not a contradiction of the segregation we applied earlier.</b>
 * Ports are split by <i>reason to depend</i>, so that {@code CreateOrderService} needs only saving and
 * {@code TrackOrderService} needs only loading, and neither compiles against capability it does not use.
 * Adapters group by <i>technology</i>: one entity, one mapper, one repository, one transactional resource, so
 * splitting them across two classes would duplicate the collaborators and separate code that changes together.
 * Buckpal makes the same call with {@code AccountPersistenceAdapter} implementing two ports. Segregation serves
 * consumers; cohesion serves implementers.
 *
 * <p><b>Deliberately not annotated {@code @Repository}.</b> That stereotype's substantive benefit is
 * persistence-exception translation, which Spring Data has already applied to the proxy behind
 * {@link OrderJpaRepository} — every exception reaching this class is already a {@code DataAccessException}.
 * Adding it would suggest a translation boundary that is not here, and would attach the word "Repository" to a
 * class that is not the DDD repository. {@code @Component} states what this is: an adapter.
 *
 * <p><b>No {@code @Transactional}.</b> The transaction boundary belongs to the use case, not the storage
 * mechanism — the application service opens it, and a create that saved successfully but then failed to record
 * its event must roll back both. An adapter that opened its own transaction would silently make that
 * impossible. This is also what keeps the lazy collections loadable in {@link OrderPersistenceMapper#toDomain}:
 * the session is still open because the caller's transaction is still open.
 *
 * <p>Package-private, like every adapter here. Spring finds it by component scan; the application layer knows
 * only the two interfaces.
 */
@Component
class OrderPersistenceAdapter implements SaveOrderPort, LoadOrderPort {

    private final OrderJpaRepository orderJpaRepository;

    OrderPersistenceAdapter(OrderJpaRepository orderJpaRepository) {
        this.orderJpaRepository = orderJpaRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Absence is reported, not interpreted. Whether a missing order is an error — and which one — is the
     * application service's judgement, and it throws {@code OrderNotFoundException}. The reference logs a
     * warning and throws from inside its data-access path, which both duplicates the eventual handler's logging
     * and puts a policy decision in the layer least equipped to make it.
     */
    @Override
    public Optional<Order> loadByTrackingId(UUID trackingId) {
        return orderJpaRepository.findByTrackingId(trackingId)
                .map(OrderPersistenceMapper::toDomain);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Maps back out of the saved entity rather than returning the argument. In Slice 1 the two are equal and
     * this is pure ceremony; from Slice 2 it is the line that carries the provider-assigned {@code version}
     * into the domain, so writing it now means that slice changes nothing here. Cheap insurance, and it keeps
     * the port's contract honest: what comes back is what is stored, not what was asked for.
     */
    @Override
    public Order save(Order order) {
        OrderJpaEntity saved = orderJpaRepository.save(OrderPersistenceMapper.toJpaEntity(order));
        return OrderPersistenceMapper.toDomain(saved);
    }


}
