# application/port/in  —  INBOUND (driving) ports. Interfaces.

The outside world calls these to reach the domain. The controller depends
on the interface, not on the service.

  CreateOrderUseCase.java  — one method: UUID createOrder(CreateOrderCommand)
  GetOrderQuery.java       — one method: Order getOrder(UUID id)

command/CreateOrderCommand.java — a record carrying the inputs (customerId,
                                  restaurantId, items). Application-layer type,
                                  NOT a domain object, NOT a web DTO.
