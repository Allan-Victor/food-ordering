package com.allan.food.order.domain.model.valueobject;

/**
 * A postal delivery address.
 *
 * <p><strong>Value object.</strong> Two addresses with the same street, postal
 * code, and city are the same address; there is nothing to distinguish them, so
 * the type has no identity.
 *
 */
public record StreetAddress(
        String street,
        String postalCode,
        String city
) {
    public StreetAddress {
        if (street == null || street.isBlank()) throw new IllegalArgumentException("Street required");
        if (postalCode == null || postalCode.isBlank()) throw new IllegalArgumentException("Postal code is required");
        if (city == null || city.isBlank()) throw new IllegalArgumentException("City is required");
    }
}
