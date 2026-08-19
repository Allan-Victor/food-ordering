package com.allan.food.order.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA-side counterpart of a domain {@code OrderItem}, mapped as an element of the order's collection rather
 * than as an entity of its own.
 *
 * <p><b>The load-bearing decision — {@code @ElementCollection}, not {@code @Entity}.</b> Order items are not
 * independently addressable: you never fetch item 3 without its order, never reference one from elsewhere,
 * never delete one on its own. JPA has a vocabulary for exactly that, and using it makes the aggregate boundary
 * visible in the mapping rather than merely intended. The reference models items as a separate {@code @Entity}
 * with a composite {@code OrderItemEntityId} class — more machinery, and it advertises items as independently
 * persistable things, which contradicts the aggregate rule it is trying to express.
 *
 * <p>The usual objection to {@code @ElementCollection} is its update strategy: mutate one element and the
 * provider deletes and reinserts the whole collection. Irrelevant here, because items are immutable once the
 * order is created — the collection is written once and thereafter only read.
 *
 * <p><b>Why {@code subTotal} is stored despite being derivable</b> from price × quantity: it is a historical
 * fact about a completed transaction, not a calculation to be redone. The same reasoning that makes
 * {@code Order.reconstitute} trust the stored total applies to each line. Recomputing on read would silently
 * rewrite history the first time a menu price changes.
 *
 * <p>{@code position} is stored explicitly and is the collection's sort key on read. Because it is a real
 * column, we need no {@code @OrderColumn}: the ordering is data the domain owns and gave meaning to (1, 2, 3
 * scoped to the order), not an artefact the provider maintains for us.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
class OrderItemEmbeddable {
    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private java.util.UUID productId;

    /**
     * Captured at order time and never refreshed. If the restaurant renames the dish tomorrow, this order
     * still records what the customer actually bought — the same historical-fidelity argument as
     * {@code subTotal}.
     */
    @Column(nullable = false, length = 100)
    private String productName;

    @Column(nullable = false)
    private int quantity;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "price_amount", nullable = false, precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "price_currency", nullable = false, length = 3))
    })
    private MoneyEmbeddable price;

}
