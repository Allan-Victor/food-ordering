package com.allan.food.restaurant.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data repository over {@link OrderApprovalJpaEntity}. */
interface OrderApprovalJpaRepository extends JpaRepository<OrderApprovalJpaEntity, UUID> {

    /**
     * The idempotency lookup, backed by the unique index.
     */
    Optional<OrderApprovalJpaEntity> findByOrderId(UUID orderId);
}