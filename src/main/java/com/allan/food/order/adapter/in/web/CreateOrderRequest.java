package com.allan.food.order.adapter.in.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.UUID;

/**
 * The JSON body of {@code POST /api/v1/orders}.
 *
 * <p><b>Why this exists when {@code CreateOrderCommand} is nearly identical.</b> Not symmetry for its own sake:
 * the two shapes genuinely part company as soon as authentication exists. {@code customerId} sits in this body
 * only because Slice 1 has no principal — in any deployed system it comes from the security context, and
 * supplying it is precisely the web adapter's job. At that point the request loses a field the command keeps,
 * and the seam absorbs the change instead of the port contract.
 *
 * <p>There is a second, quieter reason. Binding straight to the command would make it a Jackson type by
 * accident; the first {@code @JsonProperty} or {@code @JsonNaming} anyone adds for a wire-format concern would
 * put a serialization library inside the application layer. Keeping the wire shape here means the application
 * layer never learns that JSON exists.
 *
 * <p><b>On duplicating the constraints.</b> They appear both here and on {@code CreateOrderCommand}, and that
 * is deliberate rather than an oversight. These fire during request binding and yield a clean 400 with
 * per-field paths a client can act on. The command's protect the <i>port</i> from every driving adapter,
 * including the messaging adapters arriving in Slice 4 that will never pass through this class. Two boundaries,
 * two guards; if both are correct the command's never fire.
 *
 * <p><b>No prices anywhere.</b> The reference's request carries {@code price} per item and a {@code price}
 * total for the order, letting the caller state what its own order costs. Ours carries product and quantity;
 * the server derives every money value from the restaurant replica.
 *
 * <p>Records deserialize natively — Jackson uses the canonical constructor, no no-arg constructor or setters
 * required, so the wire model can be immutable.
 */
record CreateOrderRequest(
        @NotNull(message = "customerId is required")
        UUID customerId,

        @NotNull(message = "restaurantId is required")
        UUID restaurantId,

        @NotEmpty(message = "an order must contain at least one item")
        @Valid
        List<Item> items,

        @NotNull(message = "a delivery address is required")
        @Valid
        Address address
) {
    /**
     * One requested line.
     *
     * <p>{@code @Positive} rather than {@code @Min(1)} — equivalent for integers, but it states the intent
     * (a quantity is a positive quantity) rather than an arbitrary bound.
     */
    record Item(
            @NotNull(message = "productId is required")
            UUID productId,

            @NotNull(message = "quantity is required")
            @Positive(message = "quantity must be greater than zero")
            Integer quantity
    ) {
    }

    /**
     * Where the order is going.
     *
     * <p>{@code @Size}, not the reference's {@code @Max} — the latter is a numeric constraint that validates
     * nothing at all when placed on a String, so its address fields are effectively unbounded despite appearing
     * guarded. Lengths match the persistence columns so validation and schema cannot drift.
     */
    record Address(
            @NotBlank(message = "street is required")
            @Size(max = 50)
            String street,

            @NotBlank(message = "postalCode is required")
            @Size(max = 10)
            String postalCode,

            @NotBlank(message = "city is required")
            @Size(max = 50)
            String city
    ) {
    }
}
