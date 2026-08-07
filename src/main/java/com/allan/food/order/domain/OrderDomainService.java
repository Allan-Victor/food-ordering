package com.allan.food.order.domain;

import com.allan.food.order.domain.event.OrderCancelledEvent;
import com.allan.food.order.domain.event.OrderCreatedEvent;
import com.allan.food.order.domain.event.OrderPaidEvent;
import com.allan.food.order.domain.model.entity.Order;
import com.allan.food.order.domain.model.entity.Restaurant;
import com.allan.food.order.domain.model.valueobject.StreetAddress;

import java.util.List;
import java.util.UUID;

/**
 * Domain service for the order lifecycle
 *
 * <p><strong>Why a domain service exists at all.</strong> Business logic
 * belongs on the aggregate that owns the data it needs. This service holds
 * only the rules that need data from <em>two</em> aggregates and therefore
 * belong to neither: creating an order requires the restaurant's menu to
 * confirm product names and prices, and {@code Order} must not reach into
 * {@code Restaurant} to get it. Everything expressible with order data alone
 * stays on {@link com.allan.food.order.domain.model.entity.Order}.
 *
 * <p><strong>Events are returned, never published.</strong> The domain states
 * what happened; the application layer decides where that fact goes. If this
 * service published to a broker it would depend on infrastructure, could not
 * be unit-tested without one, and would emit facts before the surrounding
 * transaction committed - announcing changes that may yet roll back.
 *
 * <p><strong>Alternative you will see elsewhere.</strong> Many codebases have
 * the aggregate accumulate its own events (Spring Data's
 * {@code AbstractAggregateRoot}, jMolecules, Axon) and drain them on save,
 * rather than returning them from each operation. That scales better when an
 * operation raises several events. Explicit returns are used here because the
 * flow is visible in the signature, and there is no hidden mutable state to
 * remember to drain.
 *
 * <p>Pure domain: no Spring, no JPA, no transaction management
 */
public interface OrderDomainService {
    /**
     * Creates a valid order from a customer's request, confirming every item
     * against the restaurant's menu.
     *
     * <p>This is a <em>factory</em> in Evans' sense: creation requires
     * knowledge held by another aggregate, so it cannot live in {@code Order}'s
     * own constructor. The service resolves each requested product against the
     * live menu, builds line items at the restaurant's authoritative prices,
     * and only then constructs the order.
     *
     * <p><strong>Prices are server-derived.</strong> The caller supplies
     * product identifiers and quantities, never prices. A client-supplied
     * price is untrusted input, and validating it after the fact is strictly
     * weaker than never accepting it.
     *
     * @throws com.allan.food.order.domain.exception.OrderDomainException
     *          if the restaurant is inactive or an item is not on its menu
     */
    OrderCreationResult createOrder(UUID customerId,
                                    StreetAddress deliveryAddress,
                                    List<RequestedItem> requestedItems,
                                    Restaurant restaurant);

    /** Marks the order paid. Legal only from {@code PENDING}. */
    OrderPaidEvent payOrder(Order order);

    /**
     * Marks the order approved by the restaurant. Legal only from {@code PAID}.
     *
     * <p>Raises no event: approval is a terminal success state and nothing
     * downstream reacts to it.
     */
    void approveOrder(Order order);

    /**
     * Begins compensation after a downstream failure, moving the order to
     * {@code CANCELLING} so that payment can be rolled back.
     *
     * <p>The intermediate state is a <em>sematic lock</em>: it announces that
     * compensation is in flight, so nothing treats the order as settled while
     * the refund is still outstanding.
     */
    OrderCancelledEvent cancelOrderPayment(Order order, List<String> failureMessages);

    /**
     * Completes cancellation. Legal from {@code CANCELLING} (compensation
     * finished) or {@code PENDING} (payment never happened).
     *
     * <p>Raises no event: this is the terminal state of the failure path.
     */
    void cancelOrder(Order order, List<String> failureMessages);

    /**
     * A single line of a customer's request: what they want and how many.
     *
     * <p>Deliberately carries no price. Prices come from the restaurant.
     */
    record RequestedItem(UUID productId, int quantity) {
        public RequestedItem {
            if (productId == null) {
                throw new IllegalArgumentException("ProductId is required");
            }
            if (quantity <= 0) {
                throw new IllegalArgumentException("Quantity must be positive, got: " + quantity);
            }
        }
    }

    /**
     * The created aggregate together with the fact of its creation.
     *
     * <p>Both are needed by the caller - the order to persist, the event to
     * publish - and returning them as a pair keeps the domain service free of
     * any publishing concern.
     */
    record OrderCreationResult(Order order, OrderCreatedEvent event) {}
}
