# application/service  —  the thin orchestrator.

Implements the inbound ports, calls the outbound ports. NO business rules
here — those live on the aggregate. Pattern: load/create aggregate ->
call ONE command on it -> save.

  OrderApplicationService.java  — implements CreateOrderUseCase, GetOrderQuery.
                                  @Service, @Transactional. depends on
                                  SaveOrderPort + LoadOrderPort (interfaces).
