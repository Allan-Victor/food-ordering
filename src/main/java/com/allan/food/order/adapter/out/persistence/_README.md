# adapter/out/persistence  —  DRIVEN adapter. JPA lives here (not in the core).

This whole folder is the "pure hexagonal" cost you chose to feel once.
In the pragmatic version, OrderJpaEntity + mapper vanish and the domain
Order carries @Entity directly.

  OrderJpaEntity.java          — @Entity. the PERSISTENCE model. NOT domain Order.
  OrderItemJpaEntity.java      — @Embeddable or @ElementCollection.
  OrderPersistenceMapper.java  — domain Order <-> OrderJpaEntity. plain class.
  OrderRepository.java         — Spring Data JpaRepository<OrderJpaEntity, UUID>.
  OrderPersistenceAdapter.java — @Component implementing SaveOrderPort +
                                 LoadOrderPort. uses mapper + repository.
