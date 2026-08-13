package com.allan.food.order.application.port.out;

import com.allan.food.order.domain.model.entity.Restaurant;

import java.util.Optional;
import java.util.UUID;

/**
 * Driven port for loading the read-only {@link Restaurant} replica this context needs to confirm an order's
 * lines — product existence, names, prices, and whether the restaurant is currently accepting orders.
 *
 * <p><b>The load-bearing decision — look up by identity, return {@code Optional}.</b> A driven repository is a
 * collection-like abstraction keyed on <i>identity</i> (Evans: "the illusion of an in-memory collection of all
 * objects of that type"). You ask for the restaurant by its id and receive the whole replica; the domain then
 * validates each requested line against it via {@code restaurant.requireProduct(productId)}. Validation lives
 * in the aggregate, not in the query.
 *
 * <p><b>Alternative you'll see elsewhere — and why we rejected it:</b> the reference's port is
 * {@code Optional<Restaurant> findRestaurantInformation(Restaurant restaurant)}: you pass a
 * <i>partially-built</i> Restaurant (its id plus stub products the client claimed to want) and get an enriched
 * one back. That query-by-example shape exists only to serve a design we discarded — client-supplied products
 * on the command. It inverts the repository contract (you hand it an instance of the very thing you are
 * fetching) and is really an "enrich this" RPC in a lookup's clothing. With server-owned menus we have a plain
 * identity lookup, which is the recognisable, testable, professional shape.
 *
 * <p><b>Adapter note (deferred to the persistence step):</b> this port says nothing about <i>where</i> the
 * replica lives. In Slice 1 the implementation can be an in-memory seeded map — honest about the replica's
 * provisional nature — and can later become JPA-backed or, at Slice 4, maintained by consuming Restaurant-
 * service events, all without touching this port or the application service. That swappability is the entire
 * point of stating the dependency as a port.
 */
public interface LoadRestaurantPort {
    /**
     * Loads the restaurant replica by its id.
     *
     * @param restaurantId the restaurant's identity
     * @return the replica if known to this context, otherwise empty
     */
    Optional<Restaurant> load(UUID restaurantId);

}
