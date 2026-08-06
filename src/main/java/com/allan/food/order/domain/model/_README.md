# domain/model  —  PURE. Write these FIRST.

No Spring. No JPA. No Lombok. Only the JDK.
If you import anything from org.springframework or jakarta.persistence here, stop.

Write in this order:
  1. Money.java         (record) — amount + currency, add/multiply, validation
  2. OrderStatus.java   (enum)   — PENDING, PAID, APPROVED, CANCELLED, REJECTED
  3. OrderItem.java     (record) — productId, quantity, price, lineTotal()
  4. Order.java         (class)  — aggregate root: create(), pay(), approve(),
                                   reject(), cancel(); guarded transitions;
                                   raw UUIDs for id/customerId/restaurantId
