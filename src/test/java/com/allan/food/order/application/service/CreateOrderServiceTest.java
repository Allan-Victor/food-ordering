package com.allan.food.order.application.service;

import com.allan.food.order.application.exception.RestaurantNotFoundException;
import com.allan.food.order.application.port.in.command.CreateOrderCommand;
import com.allan.food.order.application.port.in.command.CreateOrderResult;
import com.allan.food.order.application.port.out.LoadRestaurantPort;
import com.allan.food.order.application.port.out.PublishEventPort;
import com.allan.food.order.application.port.out.SaveOrderPort;
import com.allan.food.order.domain.OrderDomainService;
import com.allan.food.order.domain.OrderDomainServiceImpl;
import com.allan.food.order.domain.event.OrderCreatedEvent;
import com.allan.food.order.domain.exception.OrderDomainException;
import com.allan.food.order.domain.model.entity.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.allan.food.order.OrderTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Plain JUnit and Mockito — no Spring context. The service is a constructor away from being usable, which is
 * itself the point: an application service that needs a container to instantiate has taken on a dependency it
 * should not have.
 *
 * <p>Fresh mocks per test via {@code @BeforeEach}, not shared ones via {@code @BeforeAll}. The reference shares
 * mocks across a {@code PER_CLASS} lifecycle and then re-stubs one inside a test, leaving stubs that leak into
 * whichever test runs next. Isolation is not a style preference.
 *
 * <p>The domain service is real, not mocked. It is a pure function of its arguments with no I/O, so a stub
 * would only let the test pass while the real rules were broken. Ports are mocked because they are I/O.
 */
@DisplayName("CreateOrderService")
class CreateOrderServiceTest {

    private LoadRestaurantPort loadRestaurantPort;
    private SaveOrderPort saveOrderPort;
    private PublishEventPort publishEventPort;
    private CreateOrderService underTest;

    @BeforeEach
    void setUp() {
        loadRestaurantPort = mock(LoadRestaurantPort.class);
        saveOrderPort = mock(SaveOrderPort.class);
        publishEventPort = mock(PublishEventPort.class);
        OrderDomainService domainService = new OrderDomainServiceImpl();

        underTest = new CreateOrderService(domainService, loadRestaurantPort, saveOrderPort, publishEventPort);

        when(loadRestaurantPort.load(RESTAURANT_ID)).thenReturn(Optional.of(activeRestaurant()));
        when(saveOrderPort.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static CreateOrderCommand command() {
        return new CreateOrderCommand(
                CUSTOMER_ID,
                RESTAURANT_ID,
                List.of(new CreateOrderCommand.OrderItemDto(ROLEX_ID, 2),
                        new CreateOrderCommand.OrderItemDto(CHAPATI_ID, 3)),
                new CreateOrderCommand.OrderAddressDto("Plot 12 Kira Road", "256", "Kampala"));
    }

    @Test
    void returnsTheTrackingIdOfThePersistedOrder() {
        CreateOrderResult result = underTest.createOrder(command());

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(saveOrderPort).save(saved.capture());

        assertThat(result.trackingId()).isEqualTo(saved.getValue().trackingId());
    }

    @Test
    @DisplayName("prices the order from the replica, never from the command")
    void derivesPriceServerSide() {
        underTest.createOrder(command());

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(saveOrderPort).save(saved.capture());

        assertThat(saved.getValue().price()).isEqualTo(ugx("22000"));
        assertThat(saved.getValue().customerId()).isEqualTo(CUSTOMER_ID);
    }

    @Test
    @DisplayName("saves before publishing, so an event never precedes the state it describes")
    void savesThenPublishes() {
        underTest.createOrder(command());

        InOrder inOrder = inOrder(saveOrderPort, publishEventPort);
        inOrder.verify(saveOrderPort).save(any(Order.class));
        inOrder.verify(publishEventPort).publish(any(OrderCreatedEvent.class));
    }

    @Test
    void publishesACreationEventMatchingTheOrder() {
        underTest.createOrder(command());

        ArgumentCaptor<OrderCreatedEvent> event = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(publishEventPort).publish(event.capture());

        assertThat(event.getValue().customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(event.getValue().occurredAt()).isNotNull();
    }

    @Test
    @DisplayName("an unknown restaurant is an application failure, not a domain one")
    void unknownRestaurantThrowsAndTouchesNothing() {
        UUID unknown = UUID.randomUUID();
        when(loadRestaurantPort.load(unknown)).thenReturn(Optional.empty());

        CreateOrderCommand command = new CreateOrderCommand(
                CUSTOMER_ID, unknown,
                List.of(new CreateOrderCommand.OrderItemDto(ROLEX_ID, 1)),
                new CreateOrderCommand.OrderAddressDto("Plot 12", "256", "Kampala"));

        assertThatExceptionOfType(RestaurantNotFoundException.class)
                .isThrownBy(() -> underTest.createOrder(command))
                .satisfies(ex -> assertThat(ex.getRestaurantId()).isEqualTo(unknown));

        verifyNoInteractions(saveOrderPort, publishEventPort);
    }

    @Test
    @DisplayName("a domain refusal aborts before any side effect")
    void inactiveRestaurantPublishesNothing() {
        when(loadRestaurantPort.load(RESTAURANT_ID)).thenReturn(Optional.of(inactiveRestaurant()));

        assertThatExceptionOfType(OrderDomainException.class)
                .isThrownBy(() -> underTest.createOrder(command()));

        // The transaction would roll back anyway, but an event published before the failure would
        // already have been handed to the multicaster. Nothing should reach either port.
        verifyNoInteractions(saveOrderPort, publishEventPort);
    }

    @Test
    void offMenuProductAbortsTheOrder() {
        CreateOrderCommand command = new CreateOrderCommand(
                CUSTOMER_ID, RESTAURANT_ID,
                List.of(new CreateOrderCommand.OrderItemDto(UNKNOWN_PRODUCT_ID, 1)),
                new CreateOrderCommand.OrderAddressDto("Plot 12", "256", "Kampala"));

        assertThatExceptionOfType(OrderDomainException.class)
                .isThrownBy(() -> underTest.createOrder(command));

        verifyNoInteractions(saveOrderPort, publishEventPort);
    }
}
