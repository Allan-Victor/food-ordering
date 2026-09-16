package com.allan.food.payment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository over {@link PaymentJpaEntity}. Package-private — the persistence model does not leave
 * this package, and that this adapter uses Spring Data is knowledge that does not escape it.
 */
interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, UUID> {

    /** The idempotency lookup. Backed by the unique index, so it is an index seek rather than a scan. */
    Optional<PaymentJpaEntity> findByOrderId(UUID orderId);
}
