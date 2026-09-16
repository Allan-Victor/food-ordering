package com.allan.food.restaurant.application.port.out;

import com.allan.food.restaurant.domain.model.RestaurantAvailability;

import java.util.Optional;
import java.util.UUID;

/**
 * Driven port for reading what a restaurant can currently prepare.
 *
 * <p><b>Separated from {@code ApprovalPersistencePort}</b> because the two are backed by different technology
 * and change for different reasons. Approvals are transactional writes to this context's own table;
 * availability is reference data, served from a seeded source in Slice 2 and plausibly maintained from the
 * restaurant's operational tooling later. Ports split by reason to depend, adapters group by technology — one
 * port here would have forced a single adapter holding both a repository and a map.
 */
public interface LoadAvailabilityPort {

    Optional<RestaurantAvailability> findAvailability(UUID restaurantId);
}
