package com.allan.food.restaurant.domain.model;

import com.allan.food.restaurant.domain.exception.RestaurantDomainException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What a restaurant can currently prepare. Aggregate root, and the authoritative source in this context.
 *
 * <p><b>Deliberately not named {@code Restaurant}</b>, despite modelling the same real-world thing as the order
 * context's {@code Restaurant}. The name states what this context actually cares about: not the restaurant as
 * an entity with an address and opening hours, but the narrow question of whether it can cook this order right
 * now. Naming a model after the question it answers rather than the noun it resembles is how you keep a bounded
 * context from quietly becoming a shared one.
 *
 * <p><b>The contrast with the order context's replica is the clearest illustration of bounded contexts in this
 * codebase.</b> There, {@code Restaurant} is read-only, holds prices and exists to confirm what an order costs.
 * Here, availability is authoritative, holds no prices at all, and exists to decide whether an order can be
 * fulfilled. Same real-world entity, two models, neither able to see the other, each shaped entirely by its own
 * context's needs. Merging them into one shared class would produce a model that serves neither well and
 * couples both to every change.
 *
 * <p><b>Prices are absent, and that is load-bearing.</b> The reference's restaurant recomputes the order total
 * and rejects on mismatch. That check made sense when the client supplied prices; with server-derived pricing
 * it asks the restaurant whether it agrees with itself, and introduces a real defect — a menu price changed
 * between placement and approval would reject a perfectly valid order. The price quoted at order time is what
 * the customer agreed to and what must be honoured. This context has no business holding an opinion about it.
 *
 * <p>Availability is a set of currently-preparable product ids. Whether a product is <i>on the menu</i> was
 * already settled at order time by the ordering context; the question here is narrower and more volatile —
 * whether it can be made now. A dish can be on the menu and out of stock, and only this context knows that.
 */
public class RestaurantAvailability {
    private final UUID restaurantId;
    private final boolean acceptingOrders;
    private final Set<UUID> availableProductIds;

    private RestaurantAvailability(UUID restaurantId, boolean acceptingOrders, Set<UUID> availableProductIds) {
        if (restaurantId == null) {
            throw new RestaurantDomainException("restaurantId is required");
        }
        this.restaurantId = restaurantId;
        this.acceptingOrders = acceptingOrders;
        this.availableProductIds = Set.copyOf(availableProductIds);
    }

    public static RestaurantAvailability of(UUID restaurantId, boolean acceptingOrders,
                                            Set<UUID> availableProductIds) {
        return new RestaurantAvailability(restaurantId, acceptingOrders, availableProductIds);
    }

    /**
     * Decides whether this restaurant can prepare the requested products, and says why not if it cannot.
     *
     * <p><b>Returns a result rather than throwing or returning a boolean.</b> A rejection must travel back to
     * the saga carrying reasons the customer can act on — "Chapati is unavailable" is useful, a bare
     * {@code false} is not, and an exception would be using control flow for an outcome the saga is explicitly
     * designed to handle. This is the same rule that made {@code CustomerCredit.canAfford} a query rather than
     * a throwing operation.
     *
     * <p><b>All failures are collected, not short-circuited.</b> Rejecting on the first unavailable item would
     * mean a customer fixes one product only to be rejected again for the next. Since this is the pivot and
     * rejection triggers an expensive compensation, telling the whole truth the first time matters more here
     * than anywhere else in the flow.
     *
     * <p>An inactive restaurant returns immediately without listing products: if the kitchen is closed, which
     * dishes are in stock is not the customer's problem.
     */
    public AvailabilityCheck check(List<UUID> requestedProductIds) {
        if (!acceptingOrders) {
            return AvailabilityCheck.unavailable(
                    List.of("Restaurant %s is not currently accepting orders".formatted(restaurantId)));
        }

        List<String> reasons = requestedProductIds.stream()
                .distinct()
                .filter(productId -> !availableProductIds.contains(productId))
                .map("Product %s is currently unavailable"::formatted)
                .toList();

        return reasons.isEmpty() ? AvailabilityCheck.available() : AvailabilityCheck.unavailable(reasons);
    }

    public UUID restaurantId() {
        return restaurantId;
    }

    public boolean acceptingOrders() {
        return acceptingOrders;
    }

    public Set<UUID> availableProductIds() {
        return availableProductIds;
    }

    /**
     * The outcome of an availability check: a decision plus, when negative, the reasons.
     *
     * <p>Nested inside the aggregate that produces it, following the same reasoning that nests
     * {@code ConfirmedItem} inside {@code Order} — it is this aggregate's output shape and has no independent
     * meaning.
     *
     * <p>A record rather than a boolean-and-list pair, so the two can never be passed around inconsistently: a
     * satisfied check with reasons attached is unrepresentable.
     */
    public record AvailabilityCheck(boolean satisfied, List<String> reasons) {

        public AvailabilityCheck {
            reasons = List.copyOf(reasons);
        }

        static AvailabilityCheck available() {
            return new AvailabilityCheck(true, List.of());
        }

        static AvailabilityCheck unavailable(List<String> reasons) {
            if (reasons.isEmpty()) {
                throw new RestaurantDomainException("an unavailable result must carry at least one reason");
            }
            return new AvailabilityCheck(false, reasons);
        }
    }
}
