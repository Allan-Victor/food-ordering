package com.allan.food.payment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Spring Data repository over {@link CustomerCreditJpaEntity}, keyed by the natural customer id. */
interface CustomerCreditJpaRepository extends JpaRepository<CustomerCreditJpaEntity, UUID> {
}
