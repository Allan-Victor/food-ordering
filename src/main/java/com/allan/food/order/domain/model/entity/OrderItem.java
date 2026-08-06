package com.allan.food.order.domain.model.entity;

import com.allan.food.order.domain.model.valueobject.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * Equality is by id
 * subTotal is computed never supplied
 * </p>
 * Identity is a sequential position (1, 2, 3...) scoped to the parent
 * order, not a global UUID. "Item 2 on your order" is meaningful to a
 *  * human reading an invoice; a random UUID is not.
 *  <p>
 *  Because the parent assigns position at creation, it is the
 *  one non-final field. Everything else is fixed at construction.
 */
public final class OrderItem {
    private int position;          // assigned by Order.create()
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
     * Package-private: Only Order may assign a position, and only once.
     * This is how the aggregate root controls the identity of its children.
     * @return
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
