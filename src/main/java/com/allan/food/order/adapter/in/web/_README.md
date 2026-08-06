# adapter/in/web  —  DRIVING adapter. Spring MVC lives here (not in the core).

  OrderController.java     — @RestController. depends on the inbound ports.
                             translates HTTP <-> commands/responses.
  CreateOrderRequest.java  — web DTO (record). bean-validation annotations ok.
  OrderResponse.java       — web DTO (record). built from domain Order.
