package com.allan.food.order.domain;

import com.allan.food.order.domain.event.OrderCancelledEvent;
import com.allan.food.order.domain.event.OrderPaidEvent;
import com.allan.food.order.domain.exception.OrderDomainException;
import com.allan.food.order.domain.model.entity.Order;
import com.allan.food.order.domain.model.entity.OrderItem;
import com.allan.food.order.domain.model.valueobject.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.allan.food.order.OrderTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * No Mockito here. The collaborators are {@code Restaurant} and {@code Order} — real domain objects that are
 * cheap to build and whose behaviour is the thing under test. Mocking them would assert that we called methods,
 * not that the rules hold.
 */
@DisplayName("OrderDomainService")
class OrderDomainServiceImplTest {

    private final OrderDomainService underTest = new OrderDomainServiceImpl();

    @Test
    @DisplayName("prices the order from the menu, ignoring anything the caller might have wanted")
    void confirmsAgainstTheRestaurantMenu() {
        List<OrderDomainService.RequestedItem> requested = List.of(
                new OrderDomainService.RequestedItem(ROLEX_ID, 2),
                new OrderDomainService.RequestedItem(CHAPATI_ID, 3));

        var result = underTest.createOrder(CUSTOMER_ID, address(), requested, activeRestaurant());

        // The request carried only ids and quantities. Every name and price below came from the replica.
        assertThat(result.order().price()).isEqualTo(ugx("22000"));
        assertThat(result.order().items())
                .extracting(OrderItem::productName)
                .containsExactly("Rolex", "Chapati");
    }

    @Test
    void returnsTheOrderAndItsCreationEventTogether() {
        var result = underTest.createOrder(CUSTOMER_ID, address(),
                List.of(new OrderDomainService.RequestedItem(ROLEX_ID, 1)), activeRestaurant());

        assertThat(result.order().orderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(result.event()).isNotNull();
        assertThat(result.event().orderId()).isEqualTo(result.order().orderId());
    }

    @Test
    @DisplayName("refuses an inactive restaurant — a fact about the restaurant, checked here not in Order")
    void inactiveRestaurantIsRejected() {
        List<OrderDomainService.RequestedItem> requested =
                List.of(new OrderDomainService.RequestedItem(ROLEX_ID, 1));

        assertThatExceptionOfType(OrderDomainException.class)
                .isThrownBy(() -> underTest.createOrder(CUSTOMER_ID, address(), requested, inactiveRestaurant()))
                .withMessageContaining(RESTAURANT_ID.toString());
    }

    @Test
    @DisplayName("an off-menu product stops creation instead of being skipped")
    void unknownProductIsRejected() {
        List<OrderDomainService.RequestedItem> requested =
                List.of(new OrderDomainService.RequestedItem(UNKNOWN_PRODUCT_ID, 1));

        assertThatExceptionOfType(OrderDomainException.class)
                .isThrownBy(() -> underTest.createOrder(CUSTOMER_ID, address(), requested, activeRestaurant()));
    }

    @Test
    void payDelegatesToTheAggregateAndReturnsAnEvent() {
        Order order = pendingOrder();

        OrderPaidEvent event = underTest.payOrder(order);

        assertThat(order.orderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(event.orderId()).isEqualTo(order.orderId());
    }

    @Test
    void cancelPaymentEntersCompensationAndReturnsAnEvent() {
        Order order = paidOrder();

        OrderCancelledEvent event = underTest.cancelOrderPayment(order, List.of("provider timeout"));

        assertThat(order.orderStatus()).isEqualTo(OrderStatus.CANCELLING);
        assertThat(order.failureMessages()).containsExactly("provider timeout");
        assertThat(event.orderId()).isEqualTo(order.orderId());
    }

    @Test
    void approveAndCancelDelegateToTheAggregatesGuards() {
        Order order = paidOrder();
        underTest.approveOrder(order);
        assertThat(order.orderStatus()).isEqualTo(OrderStatus.APPROVED);

        Order second = pendingOrder();
        underTest.cancelOrder(second, List.of("changed mind"));
        assertThat(second.orderStatus()).isEqualTo(OrderStatus.CANCELLED);
    }
}
