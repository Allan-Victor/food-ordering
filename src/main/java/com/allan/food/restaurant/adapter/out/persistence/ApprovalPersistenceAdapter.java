package com.allan.food.restaurant.adapter.out.persistence;

import com.allan.food.restaurant.application.port.out.ApprovalPersistencePort;
import com.allan.food.restaurant.domain.model.OrderApproval;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Driven adapter persisting approval decisions with JPA.
 *
 * <p>Holds only the approval repository. Availability is served by {@code InMemoryAvailabilityAdapter}, a
 * separate class because it is a separate technology — see {@code LoadAvailabilityPort}.
 */
@Component
class ApprovalPersistenceAdapter implements ApprovalPersistencePort {

    private final OrderApprovalJpaRepository repository;

    ApprovalPersistenceAdapter(OrderApprovalJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<OrderApproval> findApprovalByOrderId(UUID orderId) {
        return repository.findByOrderId(orderId).map(ApprovalPersistenceMapper::toDomain);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code saveAndFlush} so a unique-constraint violation on {@code order_id} — a concurrent duplicate
     * command — surfaces here and rolls the handler back, rather than at commit. The redelivered message then
     * finds the committed decision and replays its reply.
     */
    @Override
    public OrderApproval saveApproval(OrderApproval approval) {
        OrderApprovalJpaEntity saved = repository.saveAndFlush(ApprovalPersistenceMapper.toJpaEntity(approval));
        return ApprovalPersistenceMapper.toDomain(saved);
    }
}
