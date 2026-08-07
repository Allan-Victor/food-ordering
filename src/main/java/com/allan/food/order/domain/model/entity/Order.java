package com.allan.food.order.domain.model.entity;

import com.allan.food.order.domain.exception.OrderDomainException;
import com.allan.food.order.domain.model.AggregateRoot;
import com.allan.food.order.domain.model.valueobject.Money;
import com.allan.food.order.domain.model.valueobject.OrderStatus;
import com.allan.food.order.domain.model.valueobject.StreetAddress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The order aggregate root: the consistency boundary over an order and its line
 * items, and the transaction boundary for any change to them.
 *
 * <p><strong>One validated entrance.</strong> {@link #create} is the sole way to
 * make a new order. It validates every invariant, assigns identity, and sets the
 * initial state in a single step, so there is no window in which a partially
 * built or unvalidated order exists.
 *
 * <p><strong>Reconstitution is separate from creation.</strong> {@link
 * #reconstitute} rebuilds an order from storage and runs <em>no</em> business
 * rules, because the stored state was already valid when saved and a cancelled
 * order could never pass back through {@code create}. It is package-private so
 * that only the persistence mapper uses it.
 *
 * <p><strong>State transitions live here.</strong> Each of {@code pay},
 * {@code approve}, {@code initCancel}, {@code cancel} guards the current state
 * and throws on an illegal move. These guards are also the idempotency mechanism
 * once the saga drives them by at-least-once messages: a duplicate {@code pay}
 * finds the order no longer {@code PENDING} and is rejected rather than silently
 * repeated.
 *
 * <p><strong>Encapsulation.</strong> Incoming collections are defensively copied
 * and outgoing ones exposed unmodifiable, so no caller can mutate the aggregate's
 * internals from outside the root.
 */
@AggregateRoot
public class Order {
    private final UUID orderId;
    private final UUID customerId;
    private final UUID restaurantId;
    private final UUID trackingId;
    private final StreetAddress deliveryAddress;
    private final Money price;
    private final List<OrderItem> items;

    private OrderStatus orderStatus;
    private final List<String> failureMessages = new ArrayList<>();

    private Order(UUID orderId, UUID customerId, UUID restaurantId, UUID trackingId,
                 StreetAddress deliveryAddress, Money price,
                 List<OrderItem> items, OrderStatus orderStatus) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.restaurantId = restaurantId;
        this.trackingId = trackingId;
        this.deliveryAddress = deliveryAddress;
        this.price = price;
        this.items = new ArrayList<>(items);  // defensive copy
        this.orderStatus = orderStatus;
    }

    /**
     * Creates a new, valid, {@code PENDING} order.
     *
     * <p>Enforces the aggregate invariants: at least one item, a positive total,
     * and a total that equals the sum of the line subtotals. Assigns the order id,
     * a tracking id, and sequential item positions before returning.
     */
    public static Order create(UUID customerId, UUID restaurantId,
                               StreetAddress deliveryAddress,
                               Money price, List<OrderItem> items) {

        if (customerId == null) throw new OrderDomainException("Customer Id required");
        if (restaurantId == null) throw new OrderDomainException("Restaurant Id required");
        if (deliveryAddress == null) throw new OrderDomainException("Delivery Address required");
        if (items == null || items.isEmpty()) {
            throw new OrderDomainException("An order must have at least one item");
        }
        if (price == null || !price.isGreaterThanZero()) {
            throw new OrderDomainException("Total price must be greater than zero");
        }

        // invariant: stated total == sum of line subtotals
        Money itemsTotal = items.stream()
                .map(OrderItem::subTotal)
                .reduce(Money::add)
                .orElseThrow(() -> new OrderDomainException("No items to total"));

        if (!price.equals(itemsTotal)) {
            throw new OrderDomainException("Total price %s does not equal items total %s"
                    .formatted(price.amount(), itemsTotal.amount()));
        }
        Order order =  new Order(UUID.randomUUID(), customerId, restaurantId, UUID.randomUUID(),
                deliveryAddress, price, items, OrderStatus.PENDING);
        order.assignItemPositions();
        return order;
    }

    /**
     * The root assigns identity to its children. Items arrive
     * position-less and only become identifiable inside an aggregate -
     * which is exactly right: an OrderItem has no meaning outside its Order.
     */
    private void assignItemPositions() {
        int position = 1;
        for (OrderItem item: items) {
            item.assignPosition(position++);
        }
    }

    /**
     * Rebuilds an order from persisted state without running business rules.
     * For the persistence mapper only - Package private.
     */
    static Order reconstitute(UUID orderId, UUID customerId, UUID restaurantId, UUID trackingId,
                              StreetAddress deliveryAddress, Money price,
                              List<OrderItem> items, OrderStatus orderStatus,
                              List<String> failureMessages) {
        Order order = new Order(orderId, customerId, restaurantId, trackingId,
                deliveryAddress, price, items, orderStatus);
        if (failureMessages != null) {
            order.failureMessages.addAll(failureMessages);
        }
        return order;
    }

    // ----- State Transitions -----

    /** {@code PENDING → PAID}. */
    public void pay() {
        requireStatus(OrderStatus.PENDING);
        orderStatus = OrderStatus.PAID;
    }

    /** {@code PAID → APPROVED}. */
    public void approve() {
        requireStatus(OrderStatus.PAID);
        orderStatus = OrderStatus.APPROVED;
    }

    /**
     * {@code PAID → CANCELLING}. Begins compensation after a downstream failure
     * and records why, acquiring the semantic lock while payment is rolled back.
     */
    public void initCancel(List<String> reasons) {
        requireStatus(OrderStatus.PAID);
        orderStatus = OrderStatus.CANCELLING;
        addFailureMessages(reasons);
    }

    /**
     * {@code CANCELLING → CANCELLED} (compensation finished) or
     * {@code PENDING → CANCELLED} (payment never happened).
     */
    public void cancel(List<String> reasons) {
        if (orderStatus != OrderStatus.CANCELLING && orderStatus != OrderStatus.PENDING) {
            throw new OrderDomainException("Order is not in a valid state for cancel. Current: " + orderStatus);
        }
        orderStatus = OrderStatus.CANCELLED;
        addFailureMessages(reasons);
    }

    private void requireStatus(OrderStatus required) {
        if (orderStatus != required) {
            throw new OrderDomainException(
                    "Order %s cannot transition from %s (Required: %s)"
                            .formatted(orderId, orderStatus,required));
        }
    }

    /** Accumulates history - a saga can fail at several steps. */
    private void addFailureMessages(List<String> messages) {
        if (messages == null) return;
        messages.stream()
                .filter(m -> m != null && !m.isBlank())
                .forEach(failureMessages::add);
    }

    // ----- accessors - collections exposed are unmodifiable -----
    public UUID orderId()                  { return orderId; }
    public UUID customerId()          { return customerId; }
    public UUID restaurantId()        { return restaurantId; }
    public UUID trackingId()          { return trackingId; }
    public StreetAddress deliveryAddress() { return deliveryAddress; }
    public Money price()              { return price; }
    public OrderStatus orderStatus()  { return orderStatus; }

    public List<OrderItem> items() {
        return Collections.unmodifiableList(items);
    }

    public List<String> failureMessages() {
        return Collections.unmodifiableList(failureMessages);
    }
}
