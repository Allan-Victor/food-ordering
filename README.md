# Food Ordering System — Slice 1

Order bounded context in **strict-pure hexagonal**. Spring Boot 4 / Java 25.

## Goal of this slice
Goals 2 (DDD) + 3 (hexagonal). Ends with a runnable `POST /orders`.

## Run
```bash
./mvnw spring-boot:run
```
H2 console: http://localhost:8080/h2-console  (JDBC URL: `jdbc:h2:mem:orderdb`)

## Architecture — dependencies point INWARD
```
adapter.in.web ──▶ application.port.in ──▶ domain ◀── application.port.out ◀── adapter.out.persistence
   (Spring MVC)      (interfaces)          (PURE)        (interfaces)              (JPA)
```
- **domain/** — pure Java, zero framework imports. Business rules live here.
- **application/** — use cases + port interfaces. Thin orchestration.
- **adapter/** — the only place Spring & JPA are allowed.

## Build order (write the classes in this sequence)
1. `domain/model` — Money, OrderStatus, OrderItem, Order
2. `domain/exception` — OrderDomainException
3. `application/port/in` — CreateOrderUseCase, GetOrderQuery, CreateOrderCommand
4. `application/port/out` — SaveOrderPort, LoadOrderPort
5. `application/service` — OrderApplicationService
6. `adapter/out/persistence` — entity, mapper, repository, adapter
7. `adapter/in/web` — controller, request/response DTOs
8. Run it, then add the Modulith verify test.

Each package has a `_README.md` with specifics. Delete them as you fill each in.
