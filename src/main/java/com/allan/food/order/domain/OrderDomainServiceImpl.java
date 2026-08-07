package com.allan.food.order.domain;

import com.allan.food.order.domain.event.OrderCancelledEvent;
import com.allan.food.order.domain.event.OrderCreatedEvent;
import com.allan.food.order.domain.event.OrderPaidEvent;
import com.allan.food.order.domain.exception.OrderDomainException;
import com.allan.food.order.domain.model.entity.Order;
import com.allan.food.order.domain.model.entity.OrderItem;
import com.allan.food.order.domain.model.entity.Product;
import com.allan.food.order.domain.model.entity.Restaurant;
import com.allan.food.order.domain.model.valueobject.Money;
import com.allan.food.order.domain.model.valueobject.StreetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * Default implementation of {@link OrderDomainService}
 *
 * <p>Stateless and therefore safe to share as a singleton. Constructed
 * directly by the application layer's configuration rather than annotated
 * with {@code @Service} so that the domain remains free of any framework.
 *
 * <p>SLF4J is used for logging. It is a facade with no runtime framework
 * behind it, which is the customary line teams draw when keeping a domain
 * layer dependency-free; stricter interpretations move logging out entirely.
 */
public class OrderDomainServiceImpl implements OrderDomainService{

    private static final Logger log = LoggerFactory.getLogger(OrderDomainServiceImpl.class);

    @Override
    public OrderCreationResult createOrder(UUID customerId,
                                           StreetAddress deliveryAddress,
                                           List<RequestedItem> requestedItems,
                                           Restaurant restaurant) {

        requireActive(restaurant);

        /*
        * Resolve each request against the menu. requireProduct throws on an
        * unknown item rather than skipping it: an order for something the
        * restaurant does not sell is a business error, not a no-op
        * */
        List<OrderItem> items = requestedItems.stream()
                .map(requestedItem -> {
                    Product product = restaurant.requireProduct(requestedItem.productId());
                    return OrderItem.of(
                            product.productId(),
                            product.name(),     // confirmed name, from the menu
                            requestedItem.quantity(),
                            product.price());   // confirmed price, from the menu
                })
                .toList();

        // Derived, never supplied. The total cannot disagree with its own lines.
        Money price = items.stream()
                .map(OrderItem::subTotal)
                .reduce(Money::add)
                .orElseThrow(() -> new OrderDomainException("An order must have at least one item"));

        /*
        Order.create validates its invariants and assigns identity, so the
        aggregate is complete and valid the moment it exists. There is no
        separate initialise-then-validate sequence a caller would get wrong.
         */
        Order order = Order.create(customerId, restaurant.id(), deliveryAddress, price, items);

        log.info("Order {} created for customer {} at restaurant {}",
                order.orderId(), customerId, restaurant.id());
        return new OrderCreationResult(order, OrderCreatedEvent.from(order));
    }

    @Override
    public OrderPaidEvent payOrder(Order order) {
        order.pay();    //the aggregate enforces the transition
        log.info("Order {} paid", order.orderId());
        return OrderPaidEvent.from(order);
    }

    @Override
    public void approveOrder(Order order) {
        order.approve();
        log.info("Order {} approved", order.orderId());
    }

    @Override
    public OrderCancelledEvent cancelOrderPayment(Order order, List<String> failureMessages) {
        order.initCancel(failureMessages);
        log.info("Order {} entering compensation: {}", order.orderId(), failureMessages);
        return OrderCancelledEvent.from(order);
    }

    @Override
    public void cancelOrder(Order order, List<String> failureMessages) {
        order.cancel(failureMessages);
        log.info("Order {} cancelled: {}", order.orderId(), failureMessages);
    }

    /**
     * An active restaurant cannot accept orders. Checked here rather than in
     * {@code Order} because it is a fact about the restaurant and an aggregate
     * may not reach across a boundary to read another's state.
     */
    private void requireActive(Restaurant restaurant) {
        if (!restaurant.active()) {
            throw new OrderDomainException(
                    "Restaurant %s is not currently accepting orders".formatted(restaurant.id()));
        }
    }
}
