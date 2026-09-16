
/**
 * Inbound saga adapters: driving ports invoked by the in-process event bus.
 *
 * <p><b>Rule: every {@code @TransactionalEventListener} in this package that drives a
 * database write must carry {@code @Transactional(propagation = REQUIRES_NEW)}.</b>
 *
 * <p>These listeners fire in the {@code AFTER_COMMIT} phase — after the publishing
 * transaction has ended. No transaction is bound to the thread when they run. Without
 * {@code REQUIRES_NEW}, propagation {@code REQUIRED} finds nothing to join and no
 * instruction to create one; any downstream {@code saveAndFlush} throws
 * {@code TransactionRequiredException}.
 *
 * <p>The transaction boundary belongs at the adapter — the outermost entry point —
 * not inside the orchestrator or service it delegates to.
 *
 * <p>At Slice 4, these classes are replaced by Spring Cloud Stream {@code Consumer<T>}
 * beans. The {@code REQUIRES_NEW} rule transfers unchanged: each message consumed from
 * Kafka is its own transaction, opened at the consumer method, for the same reason.
 */
package com.allan.food.restaurant.adapter.in.saga;