package com.allan.food.order.adapter.out.persistence;


import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository over {@link OrderJpaEntity}.
 *
 * <p><b>Named {@code JpaRepository}, not {@code Repository}, on purpose.</b> In DDD, "Repository" names a
 * domain concept — the collection-like abstraction over aggregates — and in our architecture that concept is
 * owned by {@code SaveOrderPort} and {@code LoadOrderPort}. This interface is not that. It is a Spring Data
 * artefact, an implementation detail of one adapter, and the name says so. The reference calls its port
 * {@code OrderRepository}, which is defensible DDD naming but leaves nothing to distinguish the domain contract
 * from the framework interface when both appear in the same codebase.
 *
 * <p>Package-private, so nothing outside this adapter can reach the persistence model. The application layer
 * sees only the ports; that this adapter happens to use Spring Data is knowledge that does not escape the
 * package.
 *
 * <p><b>On {@code findByTrackingId} and the derived-query mechanism:</b> Spring Data parses the method name and
 * generates the query. Idiomatic, and the compiler plus Spring's startup verification catch a typo in the
 * property name at context load rather than at first call. No {@code @Query} needed for a single-property
 * lookup.
 */
interface OrderJpaRepository extends JpaRepository<OrderJpaEntity, UUID> {

    /**
     * Looks up an order by its unique tracking id.
     *
     * <p>Returns {@code Optional} rather than null, per Effective Java Item 55 and Spring Data convention; the
     * adapter maps the empty case to an empty {@code Optional<Order>} and the application service decides what
     * absence means.
     *
     * <p><b>No {@code @EntityGraph} here, and the reason is worth knowing.</b> The obvious optimisation would be
     * to eager-fetch {@code items} and {@code failureMessages} in one query. Hibernate rejects it:
     * join-fetching two {@code List} collections in a single query produces a cartesian product, and it throws
     * {@code MultipleBagFetchException} at startup rather than returning wrong data. So this costs three
     * selects per load. That is acceptable — an aggregate is the unit of consistency, and loading one whole is
     * exactly what the pattern asks for. If it ever mattered, the fix is {@code @BatchSize} or modelling one
     * collection as a {@code Set}, not abandoning the aggregate boundary.
     */
    Optional<OrderJpaEntity> findByTrackingId(UUID trackingId);

}
