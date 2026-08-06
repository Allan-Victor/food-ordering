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
 * Order Aggregate root.
 * <p>
 * ONE public entrance: create(). It validates, assigns identity, and
 * sets initial state in a single step - so there is no window in which
 * an Order exists un - initialised or unvalidated.
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
     * The only way to Create a new Order.
     * It is a Business event, not an allocation
     * Validation is done at construction
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
     * Rebuilds an Order from storage. NO business rules run - this state
     * was already valid when it was saved and a CANCELLED order could
     * not legally pass through create().
     * <p>
     * Package-private: only the persistence mapper may call it.
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
    public void pay() {
        requireStatus(OrderStatus.PENDING);
        orderStatus = OrderStatus.PAID;
    }

    public void approve() {
        requireStatus(OrderStatus.PAID);
        orderStatus = OrderStatus.APPROVED;
    }

    /** Semantic lock for the compensation path */
    public void initCancel(List<String> reasons) {
        requireStatus(OrderStatus.PAID);
        orderStatus = OrderStatus.CANCELLING;
        addFailureMessages(reasons);
    }

    /** From CANCELLING (compensation) or PENDING (payment never happened). */
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

    // ----- accessors - collections are unmodifiable -----
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
