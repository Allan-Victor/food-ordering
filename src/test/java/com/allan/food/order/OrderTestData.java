package com.allan.food.order;

import com.allan.food.order.domain.model.entity.Order;
import com.allan.food.order.domain.model.entity.Product;
import com.allan.food.order.domain.model.entity.Restaurant;
import com.allan.food.order.domain.model.valueobject.Money;
import com.allan.food.order.domain.model.valueobject.StreetAddress;

import java.util.List;
import java.util.UUID;

/**
 * Object Mother for domain fixtures.
 *
 * <p>Every method returns a valid default that any test may override by building on it. The alternative —
 * assembling a {@code Restaurant} inline in each test — buries the one detail a test cares about under twenty
 * lines of irrelevant-but-required setup, and means a constructor signature change edits fifty tests instead of
 * one method. Buckpal uses the same pattern ({@code AccountTestData.defaultAccount()}).
 *
 * <p>Ids are fixed constants rather than {@code UUID.randomUUID()} so a failure message names something
 * recognisable and reruns are reproducible.
 */
public final class OrderTestData {

    private OrderTestData() {
        throw new AssertionError("No instances.");
    }

    public static final String UGX = "UGX";

    public static final UUID CUSTOMER_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    public static final UUID RESTAURANT_ID = UUID.fromString("11111111-0000-0000-0000-000000000001");

    public static final UUID ROLEX_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    public static final UUID CHAPATI_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    public static final UUID UNKNOWN_PRODUCT_ID = UUID.fromString("dddddddd-0000-0000-0000-000000000001");

    public static Money ugx(String amount) {
        return Money.of(amount, UGX);
    }

    public static Product rolex() {
        return Product.of(ROLEX_ID, "Rolex", ugx("8000"));
    }

    public static Product chapati() {
        return Product.of(CHAPATI_ID, "Chapati", ugx("2000"));
    }

    public static StreetAddress address() {
        return new StreetAddress("Plot 12 Kira Road", "256", "Kampala");
    }

    public static Restaurant activeRestaurant() {
        return Restaurant.of(RESTAURANT_ID, true, List.of(rolex(), chapati()));
    }

    public static Restaurant inactiveRestaurant() {
        return Restaurant.of(RESTAURANT_ID, false, List.of(rolex(),chapati()));
    }

    /** 2 * Rolex (16_000) + 3 * Chapati (6_000) = 22_000 UGX. */
    public static List<Order.ConfirmedItem> confirmedItems() {
        return List.of(
                new Order.ConfirmedItem(ROLEX_ID, "Rolex", 2, ugx("8000")),
                new Order.ConfirmedItem(CHAPATI_ID, "Chapati", 3, ugx("2000")));
    }

    /** A valid PENDING order totalling 22_000 UGX. */
    public static Order pendingOrder() {
        return Order.create(CUSTOMER_ID, RESTAURANT_ID, address(), confirmedItems());
    }

    public static Order paidOrder() {
        Order order = pendingOrder();
        order.pay();
        return order;
    }




}
