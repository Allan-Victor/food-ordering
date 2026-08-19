package com.allan.food.order.adapter.out.restaurant;

import com.allan.food.order.application.port.out.LoadRestaurantPort;
import com.allan.food.order.domain.model.entity.Product;
import com.allan.food.order.domain.model.entity.Restaurant;
import com.allan.food.order.domain.model.valueobject.Money;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Slice 1 implementation of {@link LoadRestaurantPort}: a hard-coded, in-memory restaurant replica.
 *
 * <p><b>Why in-memory rather than a JPA table, and why that is not laziness.</b> A replica is not this
 * context's data — it is a projection of another context's data that we hold locally so ordering does not
 * require a synchronous call to the Restaurant service. The interesting question is <i>how the replica stays
 * current</i>, and that question genuinely belongs to Slice 4, where Restaurant becomes a separate service and
 * this adapter is replaced by one maintained from consumed events. Building a JPA table now would create a
 * schema we have no legitimate way to populate and would imply the Order context owns menu data, which it does
 * not. A frankly-labelled stand-in is the more honest intermediate state, and {@link LoadRestaurantPort} makes
 * the eventual replacement a one-class change.
 *
 * <p><b>Thread safety by immutability:</b> the map is built once during construction and never mutated, so
 * concurrent {@code load} calls need no synchronisation — which matters given virtual threads are enabled and
 * every request runs on its own carrier. When Slice 4 makes this replica event-maintained it becomes mutable,
 * and that is the point at which a {@code ConcurrentHashMap} (or a real table with its own transaction
 * boundary) becomes necessary. Worth noticing that immutability is doing real work here, not just tidiness.
 *
 * <p><b>Seed data for manual testing</b> — the currency is UGX deliberately: it has zero minor units, so it
 * exercises {@code Money}'s {@code Currency.getDefaultFractionDigits()} scaling rather than letting a
 * hard-coded scale of 2 pass unnoticed. That is exactly the reference's {@code Money} bug, and seeding with a
 * two-decimal currency would have hidden it.
 * <ul>
 *   <li>Active restaurant: {@code 11111111-1111-1111-1111-111111111111}</li>
 *   <li>Inactive restaurant (exercises the "not accepting orders" path):
 *       {@code 22222222-2222-2222-2222-222222222222}</li>
 *   <li>Products: {@code aaaaaaaa-…} Rolex 8000, {@code bbbbbbbb-…} Matoke &amp; Groundnut Sauce 12000,
 *       {@code cccccccc-…} Chapati 2000</li>
 * </ul>
 */
@Component
class InMemoryRestaurantAdapter implements LoadRestaurantPort {

    static final UUID ACTIVE_RESTAURANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID INACTIVE_RESTAURANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID ROLEX_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID MATOKE_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID CHAPATI_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static final String CURRENCY = "UGX";

    private final Map<UUID, Restaurant> replicasById;

    InMemoryRestaurantAdapter() {
        this.replicasById = seed();
    }
    @Override
    public Optional<Restaurant> load(UUID restaurantId) {
        return Optional.ofNullable(replicasById.get(restaurantId));
    }
    /**
     * Builds the fixed replica set.
     *
     * <p>Kept as a single private method so that the eventual JPA- or event-backed adapter has exactly one
     * place to replace, and so the seed values are readable as data rather than scattered through wiring.
     */
    private static Map<UUID, Restaurant> seed() {
        List<Product> menu = List.of(
                Product.of(ROLEX_ID, "Rolex", Money.of("8000", CURRENCY)),
                Product.of(MATOKE_ID, "Matoke & Groundnut Sauce", Money.of("12000", CURRENCY)),
                Product.of(CHAPATI_ID, "Chapati", Money.of("2000", CURRENCY)));

        return Map.of(
                ACTIVE_RESTAURANT_ID, Restaurant.of(ACTIVE_RESTAURANT_ID, true, menu),
                INACTIVE_RESTAURANT_ID, Restaurant.of(INACTIVE_RESTAURANT_ID, false, menu));
    }
}
