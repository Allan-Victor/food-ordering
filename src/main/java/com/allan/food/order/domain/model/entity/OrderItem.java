package com.allan.food.order.domain.model.entity;

import com.allan.food.order.domain.model.valueobject.Money;
import com.allan.food.order.domain.model.valueobject.StreetAddress;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A single line on an order: a product, a quantity, and the price agreed for it.
 *
 * <p><strong>Entity, not value object.</strong> Two lines reading "2 × Rolex"
 * are genuinely distinct lines — a customer may add the same product twice on
 * purpose — so they need identity. A value object would collapse them in a
 * {@code Set} and make them indistinguishable when editing one.
 *
 * <p><strong>Identity is a position, not a UUID.</strong> The identity is a
 * sequential position (1, 2, 3…) scoped to the parent order. "Item 2 on your order" is
 * meaningful on an invoice; a random UUID is not. The position is supplied by
 * the {@link Order} root at creation — see
 * {@link Order#create} — because an order item has no identity, and no meaning,
 * outside the aggregate that contains it.
 *
 * <p><strong>Fully immutable.</strong> Every field is final and set once. An
 * earlier shape assigned the position after construction through a
 * package-private mutator, which meant an item briefly existed without identity
 * and required a guard against reassignment. Taking the position as a
 * constructor argument removes both the mutable field and the guard: the item is
 * complete the moment it exists. Immutability is also what makes this safe to
 * share across threads without synchronisation.
 *
 * <p><strong>Price is captured, subtotal is derived.</strong> The price is the
 * amount confirmed from the menu at the moment of ordering and is frozen here,
 * so a later menu change cannot retroactively alter a placed order. The subtotal
 * is computed as {@code price × quantity}; taking it as a constructor argument
 * would invite a value that disagrees with its own definition.
 */
public final class OrderItem {
    private final int position;
    private final UUID productId;
    private final String productName;
    private final int quantity;
    private final Money price;
    private final Money subTotal;

    private OrderItem(int position, UUID productId, String productName, int quantity, Money price) {
        if (position <= 0) throw new IllegalArgumentException("position must be positive: " +position);
        if (productId == null) throw  new IllegalArgumentException("productId required");
        if (price == null) throw new IllegalArgumentException("price required");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive: " + quantity);
        if (!price.isGreaterThanZero()) throw new IllegalArgumentException("price must be > 0");
        if (productName == null || productName.isBlank()) throw new IllegalArgumentException("productName is required");

        this.position = position;
        this.productId = productId;
        this.productName = productName;
        this.quantity = quantity;
        this.price = price;
        this.subTotal = price.multiply(quantity); // derived, always consistent
    }

    /**
     * Creates a line item at the given position within its order.
     *
     * <p>Package-private: only {@link Order} may create order items, because
     * only the root knows the correct position and only the root may extend its
     * own boundary. Callers outside the aggregate express what they want through
     * {@code Order.create}, not by assembling items directly.
     */
    static OrderItem of(int position, UUID productId, String productName, int quantity, Money price) {
        return new OrderItem(position,productId, productName, quantity, price);
    }


    /*
     * Accessors use the no-"get" style (id() not getId()) to match
     * what Java records generate — so the domain reads consistently
     * whether a type is a record (Money) or a class (OrderItem).
     * JavaBean-style getX() lives in the adapter layer, where
     * Jackson and Hibernate expect it.
     */
    public int position()            { return position; }
    public UUID productId()     { return productId; }
    public String productName() { return productName; }
    public int quantity()       { return quantity; }
    public Money price()        { return price; }
    public Money subTotal()     { return subTotal; }


    /**
     * Entity equality: by position within the parent order.
     *
     * <p>The class is {@code final}, so {@code instanceof} is symmetric here and
     * the usual {@code getClass()}-versus-{@code instanceof} dilemma does not
     * arise — no subclass can exist to break it.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true; // means an unassigned item still equals itself
        if (!(o instanceof OrderItem orderItem)) return false;
        return position == orderItem.position; // unassigned == no identity
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(position);
    }
}
