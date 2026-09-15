package com.allan.food.order.application.port.in.command;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.UUID;

/**
 * The validated intent to create an order, crossing the driving boundary into the application.
 *
 * <p><b>The load-bearing decision - no money crosses the boundary inbound.</b> The command carries only
 * {@code productId} and {@code quantity} per line. Unit price, line sub-total and order total are all
 * derived server-side from the {@code Restaurant} replica during creation. This is precisely why
 * {@code Order.create} consumes {@code ConfirmedItem}s: the command expresses <i>what the customer
 * wants</i>, never <i>what it costs</i>.
 *
 * <p>Secondary choices: a{@code record} over a Lombok class; constraints on components as the port's
 * structural contract; and the two input shapes nested <i>inside</i> the command - mirroring how
 * {@code Order} nests {@code ConfirmedItem}/{@code PersistedItem}. Nesting states ownership and dodges a
 * name clash with the domain's own {@code OrderItem}/{@code StreetAddress}.
 */
public record CreateOrderCommand(
        @NotNull
        UUID customerId,

        @NotNull
        UUID restaurantId,

        @NotEmpty
        @Valid
        List<OrderItemDto> items,

        @NotNull
        @Valid
        OrderAddressDto address
) {
    /** Defensive copy in - the command owns an immutable snapshot of its lines. */
    public CreateOrderCommand{
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * One requested line: a product and how many of it.
     *
     * <p><b>Decision:</b> {@code productId} + {@code quantity} only. The reference carries price and
     * sub-total here; we derive both from the restaurant's menu.
     */
    public record OrderItemDto(
            @NotNull
            UUID productId,

            @NotNull
            @Positive
            Integer quantity
    ){}

    /**
     * The delivery-address input shape.
     *
     * <p><b>Decision:</b> {@code @Size(max=…)}, not {@code @Max}. The reference annotates these Strings
     * with {@code @Max} — a numeric constraint that silently does nothing on a String (latent bug). No
     * {@code id} field either: a typed-in address has no identity (contrast the reference's phantom id).
     */
    public record OrderAddressDto(
            @NotBlank
            @Size(max = 50)
            String street,

            @NotBlank
            @Size(max = 10)
            String postalCode,

            @NotBlank
            @Size(max = 50)
            String city

    ) {}

}
