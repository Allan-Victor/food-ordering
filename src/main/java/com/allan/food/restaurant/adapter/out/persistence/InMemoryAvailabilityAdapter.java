package com.allan.food.restaurant.adapter.out.persistence;

import com.allan.food.restaurant.application.port.out.LoadAvailabilityPort;
import com.allan.food.restaurant.domain.model.RestaurantAvailability;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Slice 2 implementation of {@link LoadAvailabilityPort}: a seeded, immutable availability source.
 *
 * <p><b>In-memory, but not for the same reason the order context's restaurant replica is.</b> That one is a
 * stand-in for data this context does not own and cannot yet obtain. This is authoritative data that simply has
 * no management interface yet — no admin screen, no way for a restaurant to mark a dish sold out. The
 * distinction matters for what replaces it: the replica becomes event-maintained at Slice 4, whereas this
 * becomes a table with a UI in front of it.
 *
 * <p>Immutable after construction, so concurrent reads need no synchronisation. That stops being true the
 * moment availability becomes editable, which is the point at which it needs a real table with its own
 * transaction boundary.
 *
 * <p><b>Seed data is aligned with the order context's replica by design.</b> The restaurant ids and product ids
 * match {@code InMemoryRestaurantAdapter}'s, because in a real system both would be projections of the same
 * upstream truth. The deliberate exception is {@code CHAPATI}, which is on the menu but marked unavailable —
 * that single line is what makes the pivot's rejection path reachable end to end, and therefore what makes
 * compensation observable.
 */
@Component
class InMemoryAvailabilityAdapter implements LoadAvailabilityPort {

    private static final UUID ACTIVE_RESTAURANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INACTIVE_RESTAURANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID ROLEX_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID MATOKE_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID CHAPATI_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private final Map<UUID, RestaurantAvailability> byRestaurantId = Map.of(

            // Rolex and Matoke can be prepared; Chapati is on the menu but out of stock, which is
            // what makes an order containing it exercise the pivot's rejection path.
            ACTIVE_RESTAURANT_ID,
            RestaurantAvailability.of(ACTIVE_RESTAURANT_ID, true, Set.of(ROLEX_ID, MATOKE_ID)),

            INACTIVE_RESTAURANT_ID,
            RestaurantAvailability.of(INACTIVE_RESTAURANT_ID, false, Set.of(ROLEX_ID, MATOKE_ID, CHAPATI_ID)));

    @Override
    public Optional<RestaurantAvailability> findAvailability(UUID restaurantId) {
        return Optional.ofNullable(byRestaurantId.get(restaurantId));
    }
}
