package com.allan.food.order.domain.model.entity;

import com.allan.food.order.domain.model.valueobject.Money;

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
 * <p><strong>Identity is a position, not a UUID.</strong> The id is a sequential
 * position (1, 2, 3…) scoped to the parent order. "Item 2 on your order" is
 * meaningful on an invoice; a random UUID is not. The position is assigned by
 * the {@link Order} root at creation — see {@link #assignPosition} — which is
 * why it is the one non-final field.
 *
 * <p><strong>Price is captured, subtotal is derived.</strong> The price is the
 * amount confirmed from the menu at the moment of ordering and is frozen here,
 * so a later menu change cannot retroactively alter a placed order. The subtotal
 * is computed as {@code price × quantity}; taking it as a constructor argument
 * would invite a value that disagrees with its own definition.
 */
public final class OrderItem {
    private int position;          // 0 until assigned by Order.create()
    private final UUID productId;
    private final String productName;
    private final int quantity;
    private final Money price;
    private final Money subTotal;

    private OrderItem(UUID productId, String productName, int quantity, Money price) {
        if (productId == null) throw  new IllegalArgumentException("productId required");
        if (price == null) throw new IllegalArgumentException("price required");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive: " + quantity);
        if (!price.isGreaterThanZero()) throw new IllegalArgumentException("price must be > 0");

        this.productId = productId;
        this.productName = productName;
        this.quantity = quantity;
        this.price = price;
        this.subTotal = price.multiply(quantity); // derived, always consistent
    }

    public static OrderItem of(UUID productId, String productName, int quantity, Money price) {
        return new OrderItem(productId, productName, quantity, price);
    }

    /**
     * Assigns this item's position within its order.
     *
     * <p>Package-private and single-use: only {@link Order} may call it, and
     * only once. This is the aggregate root controlling the identity of its
     * children — nothing outside the boundary can number or renumber items.
     */
    void assignPosition(int position) {
        if (this.position != 0) {
            throw new IllegalStateException("Position already assigned: " +this.position);
        }
        if (position <= 0) {
            throw new IllegalArgumentException("Position must be positive");
        }
        this.position = position;
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
     * <p>An unassigned item (position 0) equals only itself, by reference. Two
     * not-yet-parented items have no identity to compare, so they are treated as
     * unequal — which keeps them from colliding in a {@code Set} before the
     * order has numbered them.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true; // means an unassigned item still equals itself
        if (!(o instanceof OrderItem orderItem)) return false;
        return position != 0 && position == orderItem.position; // unassigned == no identity
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(position);
    }
}
