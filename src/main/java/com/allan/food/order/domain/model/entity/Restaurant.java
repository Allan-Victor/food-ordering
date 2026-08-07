package com.allan.food.order.domain.model.entity;

import com.allan.food.order.domain.exception.OrderDomainException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A read-only projection of a restaurant, as the Order context sees it.
 *
 * <p><strong>Not an aggregate root in this context.</strong> The Order service
 * never modifies a restaurant; it queries this replica to confirm product names
 * and prices when placing an order. The authoritative {@code Restaurant}
 * aggregate — with its own lifecycle and invariants — lives in the Restaurant
 * service. Modelling it as a root here would falsely imply the Order context
 * owns it. This is the standard cross-context replica pattern.
 *
 * <p><strong>Products are indexed by id at construction.</strong> Confirming an
 * order means one lookup per line item, so the menu is stored as a map. A
 * teaching version scans the product list for every item — an O(n·m) nested
 * loop — and the fix belongs in the model, not in the caller.
 */
public class Restaurant {
    private final UUID id;
    private final boolean active;
    private final Map<UUID, Product> productsById;

    private Restaurant(UUID id, boolean active, List<Product> products) {
        if (id == null) {
            throw new IllegalArgumentException("Restaurant id is required");
        }
        if (products == null || products.isEmpty()) {
            throw new IllegalArgumentException("Restaurant must have at least one product");
        }
        this.id = id;
        this.active = active;
        this.productsById = products.stream()
                .collect(Collectors.toUnmodifiableMap(Product::productId, Function.identity()));
    }

    public static Restaurant of(UUID id, boolean active, List<Product> products) {
        return new Restaurant(id, active, products);
    }

    public UUID id() {
        return id;
    }

    public boolean active() {
        return active;
    }

    /** The menu. Already unmodifiable — it is the values view of an unmodifiable map. */
    public Collection<Product> products() {
        return productsById.values();
    }

    /**
     * Resolves a product on this restaurant's menu.
     *
     * <p>Throws rather than returning {@code null} or an {@code Optional}: an
     * order referencing a product the restaurant does not sell is a business
     * error that must stop order creation, not a condition for the caller to
     * quietly handle.
     */
    public Product requireProduct(UUID productId) {
        Product product = productsById.get(productId);
        if (product == null) {
            throw new OrderDomainException(
                    String.format("Product %s is not on restaurant %s's menu", productId, id));
        }
        return product;
    }
}
