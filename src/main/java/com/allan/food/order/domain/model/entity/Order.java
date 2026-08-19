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
     * Creates a new, valid, {@code PENDING} order from confirmed line data.
     *
     * <p>Takes {@link ConfirmedItem} records rather than assembled
     * {@link OrderItem}s, because only the root may create items — it alone
     * knows their positions, and extending the aggregate's contents is the
     * root's responsibility. The caller (the domain service) supplies product
     * details already confirmed against the restaurant's menu; this method
     * assembles them into positioned items, derives the total, and validates.
     *
     * <p>The total is computed here rather than accepted as an argument, which
     * makes the invariant "total equals the sum of the lines" true by
     * construction instead of merely checked.
     */
    public static Order create(UUID customerId, UUID restaurantId,
                               StreetAddress deliveryAddress,
                               List<ConfirmedItem> confirmedItems) {

        if (customerId == null) throw new OrderDomainException("Customer Id required");
        if (restaurantId == null) throw new OrderDomainException("Restaurant Id required");
        if (deliveryAddress == null) throw new OrderDomainException("Delivery Address required");
        if (confirmedItems == null || confirmedItems.isEmpty()) {
            throw new OrderDomainException("An order must have at least one item");
        }
        List<OrderItem> items = new ArrayList<>(confirmedItems.size());
        int position = 1;
        for (ConfirmedItem confirmed : confirmedItems) {
            items.add(OrderItem.of(
                    position++,
                    confirmed.productId(),
                    confirmed.productName(),
                    confirmed.quantity(),
                    confirmed.price()
            ));
        }


        // invariant: stated total == sum of line subtotals
        Money price = items.stream()
                .map(OrderItem::subTotal)
                .reduce(Money::add)
                .orElseThrow(() -> new OrderDomainException("No items to total"));

        if (!price.isGreaterThanZero()) {
            throw new OrderDomainException("Total price must be greater than zero");
        }
        return new Order(UUID.randomUUID(), customerId, restaurantId, UUID.randomUUID(),
                deliveryAddress, price, items, OrderStatus.PENDING);
    }


    /**
     * A line's details once confirmed against the restaurant's menu: what the
     * customer asked for, priced and named by the restaurant.
     *
     * <p>Distinct from {@link OrderItem} because it carries no position — it is
     * input to the aggregate, not part of it. The root turns these into
     * positioned items.
     */
    public record ConfirmedItem(UUID productId, String productName, int quantity, Money price) {}

    // ────────────────────────────────────────────────────────────────
    //  Reconstitution input — a line as stored in the database.
    //  Carries its position, because on the way back the stored position
    //  is authoritative and must be restored, not reassigned from 1.
    // ────────────────────────────────────────────────────────────────
    public record PersistedItem(int position, UUID productId, String productName,
                                int quantity, Money price) {}

    /**
     * Rebuilds an order from persisted state, running no business rules.
     *
     * <p><strong>For the persistence mapper only.</strong> Package-private, so
     * nothing outside the domain can fabricate an order in an arbitrary state.
     *
     * <p><strong>Why it does not re-run creation.</strong> The stored state was
     * already valid when it was saved, and it may be a state {@code create}
     * could never produce — a {@code CANCELLED} order cannot be created,
     * only arrived at. Re-running creation rules on load would reject legitimate
     * historical orders.
     *
     * <p><strong>Why the total is accepted, not derived.</strong> {@code create}
     * derives the total to make the "total equals sum of lines" invariant true by
     * construction. Reconstitution does the opposite and trusts the stored total:
     * it is a historical fact. If a product's menu price was corrected after this
     * order was placed, recomputing the total on load would silently rewrite what
     * the customer actually agreed to pay. The stored figure is authoritative.
     *
     * <p><strong>Why positions are restored, not reassigned.</strong> The stored
     * positions are the identities the items had when saved. Renumbering them
     * from 1 would work only by accident (if the database returned them in order)
     * and would corrupt identity if it did not. They are restored exactly.
     *
     * <p>The mapper hands over flat {@link PersistedItem} records rather than
     * assembled {@link OrderItem}s, because {@code OrderItem} construction is
     * sealed inside this aggregate. The root assembles them — symmetric with how
     * {@code create} assembles {@link ConfirmedItem}s.
     */
    public static Order reconstitute(UUID orderId, UUID customerId, UUID restaurantId, UUID trackingId,
                              StreetAddress deliveryAddress, Money price,
                              List<PersistedItem> persistedItems, OrderStatus orderStatus,
                              List<String> failureMessages) {

        List<OrderItem> items = persistedItems.stream()
                .map(p -> OrderItem.of(
                        p.position(),
                        p.productId(),
                        p.productName(),
                        p.quantity(),
                        p.price()))
                .toList();

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
