package com.allan.food.restaurant.application.service;

import com.allan.food.restaurant.application.port.out.ApprovalPersistencePort;
import com.allan.food.restaurant.application.port.out.LoadAvailabilityPort;
import com.allan.food.restaurant.application.port.out.RestaurantReplyPort;
import com.allan.food.restaurant.domain.model.ApprovalStatus;
import com.allan.food.restaurant.domain.model.OrderApproval;
import com.allan.food.restaurant.domain.model.RestaurantAvailability;
import com.allan.food.saga.contract.SagaContract.ApproveOrder;
import com.allan.food.saga.contract.SagaContract.OrderApproved;
import com.allan.food.saga.contract.SagaContract.OrderRejected;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orchestration unit tests for {@link RestaurantApprovalService}: plain JUnit and Mockito, no Spring context, no
 * database. Same discipline as {@code OrderSagaTest} and {@code PaymentServiceTest} — ports mocked,
 * {@link RestaurantAvailability} and {@link OrderApproval} exercised as real domain objects since their rules
 * (an inactive restaurant rejects immediately, a rejection must carry at least one reason) are pure logic worth
 * exercising for real.
 */
@DisplayName("RestaurantApprovalService")
class RestaurantApprovalServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2024-06-01T12:00:00Z");

    private static final UUID RESTAURANT_ID = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID ORDER_ID = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
    private static final UUID ROLEX_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID CHAPATI_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

    private ApprovalPersistencePort persistence;
    private LoadAvailabilityPort availability;
    private RestaurantReplyPort replyPort;
    private RestaurantApprovalService underTest;

    @BeforeEach
    void setUp() {
        persistence = mock(ApprovalPersistencePort.class);
        availability = mock(LoadAvailabilityPort.class);
        replyPort = mock(RestaurantReplyPort.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

        underTest = new RestaurantApprovalService(persistence, availability, replyPort, clock);

        when(persistence.saveApproval(any(OrderApproval.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static ApproveOrder approveOrder(UUID... productIds) {
        List<ApproveOrder.Line> lines = List.of(
                java.util.Arrays.stream(productIds)
                        .map(id -> new ApproveOrder.Line(id, 1))
                        .toArray(ApproveOrder.Line[]::new));
        return new ApproveOrder(UUID.randomUUID(), ORDER_ID, RESTAURANT_ID, lines, FIXED_INSTANT);
    }

    @Test
    @DisplayName("an order the restaurant can prepare is approved, persisted before the reply is sent")
    void satisfiableOrderIsApproved() {
        when(persistence.findApprovalByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(availability.findAvailability(RESTAURANT_ID)).thenReturn(Optional.of(
                RestaurantAvailability.of(RESTAURANT_ID, true, Set.of(ROLEX_ID, CHAPATI_ID))));

        ApproveOrder command = approveOrder(ROLEX_ID);
        underTest.onApproveOrder(command);

        InOrder order = inOrder(persistence, replyPort);
        order.verify(persistence).saveApproval(any(OrderApproval.class));
        order.verify(replyPort).approved(any(OrderApproved.class));

        ArgumentCaptor<OrderApproval> captor = ArgumentCaptor.forClass(OrderApproval.class);
        verify(persistence).saveApproval(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(captor.getValue().orderId()).isEqualTo(ORDER_ID);

        ArgumentCaptor<OrderApproved> replyCaptor = ArgumentCaptor.forClass(OrderApproved.class);
        verify(replyPort).approved(replyCaptor.capture());
        assertThat(replyCaptor.getValue().sagaId()).isEqualTo(command.sagaId());
        verify(replyPort, never()).rejected(any());
    }

    @Test
    @DisplayName("an unavailable product rejects with a reason naming it, and persists the rejection")
    void unavailableProductIsRejected() {
        when(persistence.findApprovalByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(availability.findAvailability(RESTAURANT_ID)).thenReturn(Optional.of(
                RestaurantAvailability.of(RESTAURANT_ID, true, Set.of(ROLEX_ID)))); // no CHAPATI

        underTest.onApproveOrder(approveOrder(CHAPATI_ID));

        ArgumentCaptor<OrderApproval> captor = ArgumentCaptor.forClass(OrderApproval.class);
        verify(persistence).saveApproval(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(captor.getValue().reasons()).anySatisfy(
                reason -> assertThat(reason).contains(CHAPATI_ID.toString()));

        ArgumentCaptor<OrderRejected> replyCaptor = ArgumentCaptor.forClass(OrderRejected.class);
        verify(replyPort).rejected(replyCaptor.capture());
        assertThat(replyCaptor.getValue().reasons()).isEqualTo(captor.getValue().reasons());
        verify(replyPort, never()).approved(any());
    }

    @Test
    @DisplayName("an unknown restaurant rejects rather than throwing — unfulfillable, not retriable")
    void unknownRestaurantIsRejected() {
        when(persistence.findApprovalByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(availability.findAvailability(RESTAURANT_ID)).thenReturn(Optional.empty());

        underTest.onApproveOrder(approveOrder(ROLEX_ID));

        verify(replyPort).rejected(any(OrderRejected.class));
        verify(replyPort, never()).approved(any());
    }

    @Test
    @DisplayName("a duplicate ApproveOrder for an already-approved order re-sends the approval without re-deciding")
    void duplicateApproveOrderReplaysApproval() {
        OrderApproval existing = OrderApproval.approved(ORDER_ID, RESTAURANT_ID, FIXED_INSTANT);
        when(persistence.findApprovalByOrderId(ORDER_ID)).thenReturn(Optional.of(existing));

        underTest.onApproveOrder(approveOrder(ROLEX_ID));

        verify(replyPort).approved(any(OrderApproved.class));
        verify(persistence, never()).saveApproval(any());
        verify(availability, never()).findAvailability(any());
    }

    @Test
    @DisplayName("a duplicate ApproveOrder for an already-rejected order re-sends the same recorded reasons")
    void duplicateApproveOrderReplaysRejection() {
        OrderApproval existing = OrderApproval.rejected(
                ORDER_ID, RESTAURANT_ID, List.of("Product unavailable"), FIXED_INSTANT);
        when(persistence.findApprovalByOrderId(ORDER_ID)).thenReturn(Optional.of(existing));

        underTest.onApproveOrder(approveOrder(ROLEX_ID));

        ArgumentCaptor<OrderRejected> captor = ArgumentCaptor.forClass(OrderRejected.class);
        verify(replyPort).rejected(captor.capture());
        assertThat(captor.getValue().reasons()).containsExactly("Product unavailable");
        verify(persistence, never()).saveApproval(any());
        verify(availability, never()).findAvailability(any());
    }

    @Test
    @DisplayName("an inactive restaurant rejects without inspecting individual products")
    void inactiveRestaurantIsRejectedWithoutListingProducts() {
        when(persistence.findApprovalByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(availability.findAvailability(RESTAURANT_ID)).thenReturn(Optional.of(
                RestaurantAvailability.of(RESTAURANT_ID, false, Set.of(ROLEX_ID, CHAPATI_ID))));

        underTest.onApproveOrder(approveOrder(ROLEX_ID, CHAPATI_ID));

        ArgumentCaptor<OrderRejected> captor = ArgumentCaptor.forClass(OrderRejected.class);
        verify(replyPort).rejected(captor.capture());
        assertThat(captor.getValue().reasons()).hasSize(1);
        assertThat(captor.getValue().reasons().getFirst()).contains("not currently accepting orders");
    }
}
