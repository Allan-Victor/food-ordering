package com.allan.food.order.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA-side counterpart of the domain's {@code StreetAddress} value object.
 *
 * <p>Embedded rather than given its own table, because that is what a value object <i>is</i>: the address has no
 * identity and no lifecycle apart from the order it belongs to, so it has no business being separately
 * addressable. The schema should say the same thing the model says.
 *
 * <p><b>Note the absent column.</b> The reference's address entity carries an {@code id}, populated with a
 * freshly-minted {@code UUID.randomUUID()} at mapping time. That id is never looked up, never referenced, and
 * differs on every write of the same address — a column that exists only because the codebase reflexively gives
 * everything an id. Column lengths mirror the command's {@code @Size} constraints, so validation and schema
 * agree instead of drifting.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
class StreetAddressEmbeddable {
    @Column(nullable = false, length = 50)
    private String street;

    @Column(nullable = false, length = 10)
    private String postalCode;

    @Column(nullable = false, length = 50)
    private String city;
}
