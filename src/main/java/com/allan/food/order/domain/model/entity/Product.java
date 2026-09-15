package com.allan.food.order.domain.model.entity;

import com.allan.food.order.domain.model.valueobject.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * A menu item as the restaurant defines it: an authoritative name and price
 * for a product identifier.
 *
 * <p><strong>Entity, but immutable.</strong> Identity is the product id, so
 * equality is by id. The Order context only ever <em>reads</em> products - they
 * arrive as part of the restaurant replica used to confirm an order - so there
 * is no mutator.
 */
public final class Product {
    private final UUID productId;
    private final String name;
    private final Money price;

    private Product(UUID productId, String name, Money price) {
        if (productId == null) {
            throw new IllegalArgumentException("Product id is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Product name is required");
        }
        if (price == null || !price.isGreaterThanZero()) {
            throw new IllegalArgumentException("Product price must be greater than zero");
        }
        this.productId = productId;
        this.name = name;
        this.price = price;
    }

    public static Product of(UUID id, String name, Money price) {
        return new Product(id, name, price);
    }

    public UUID productId() {
        return productId;
    }

    public String name() {
        return name;
    }

    public Money price() {
        return price;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Product product)) return false;
        return Objects.equals(productId, product.productId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(productId);
    }
}
