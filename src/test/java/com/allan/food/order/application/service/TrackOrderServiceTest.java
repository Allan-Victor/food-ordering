package com.allan.food.order.application.service;

import com.allan.food.order.application.exception.OrderNotFoundException;
import com.allan.food.order.application.port.in.command.TrackOrderQuery;
import com.allan.food.order.application.port.in.command.TrackOrderResponse;
import com.allan.food.order.application.port.out.LoadOrderPort;
import com.allan.food.order.domain.model.entity.Order;
import com.allan.food.order.domain.model.valueobject.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.allan.food.order.OrderTestData.paidOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("TrackOrderService")
class TrackOrderServiceTest {

    private LoadOrderPort loadOrderPort;
    private TrackOrderService underTest;

    @BeforeEach
    void setUp() {
        loadOrderPort = mock(LoadOrderPort.class);
        underTest = new TrackOrderService(loadOrderPort);
    }

    @Test
    void reportsStatusAndFailureMessages() {
        Order order = paidOrder();
        order.initCancel(List.of("provider timeout"));
        when(loadOrderPort.loadByTrackingId(order.trackingId())).thenReturn(Optional.of(order));

        TrackOrderResponse response = underTest.trackOrder(new TrackOrderQuery(order.trackingId()));

        assertThat(response.trackingId()).isEqualTo(order.trackingId());
        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLING);
        assertThat(response.failureMessages()).containsExactly("provider timeout");
    }

    @Test
    @DisplayName("an unknown tracking id is a 404-shaped failure, decided here not in the adapter")
    void unknownTrackingIdThrows() {
        UUID unknown = UUID.randomUUID();
        when(loadOrderPort.loadByTrackingId(unknown)).thenReturn(Optional.empty());

        assertThatExceptionOfType(OrderNotFoundException.class)
                .isThrownBy(() -> underTest.trackOrder(new TrackOrderQuery(unknown)))
                .satisfies(ex -> assertThat(ex.getTrackingId()).isEqualTo(unknown));
    }

    @Test
    void exposesAnUnmodifiableFailureList() {
        Order order = paidOrder();
        when(loadOrderPort.loadByTrackingId(order.trackingId())).thenReturn(Optional.of(order));

        assertThat(underTest.trackOrder(new TrackOrderQuery(order.trackingId())).failureMessages())
                .isUnmodifiable();
    }
}
