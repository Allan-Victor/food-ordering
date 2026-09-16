package com.allan.food.order.application.saga;

import com.allan.food.order.application.port.out.LoadOrderPort;
import com.allan.food.order.application.port.out.PaymentCommandPort;
import com.allan.food.order.application.port.out.RestaurantCommandPort;
import com.allan.food.order.application.port.out.SaveOrderPort;
import com.allan.food.order.domain.event.OrderCreatedEvent;
import com.allan.food.order.domain.model.entity.Order;
import com.allan.food.saga.contract.SagaContract.ApproveOrder;
import com.allan.food.saga.contract.SagaContract.OrderApproved;
import com.allan.food.saga.contract.SagaContract.OrderRejected;
import com.allan.food.saga.contract.SagaContract.PaymentCompleted;
import com.allan.food.saga.contract.SagaContract.PaymentFailed;
import com.allan.food.saga.contract.SagaContract.PaymentRefunded;
import com.allan.food.saga.contract.SagaContract.ProcessPayment;
import com.allan.food.saga.contract.SagaContract.RefundPayment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.allan.food.order.OrderTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Orchestration unit tests for {@link OrderSaga}: plain JUnit and Mockito, no Spring context, no database.
 *
 * <p>This is the "orchestration unit test" layer CLAUDE.md Section 5 asks for, distinct from the full saga
 * integration test that exercises the real {@code @TransactionalEventListener(AFTER_COMMIT)} wiring. Here every
 * port is a mock and every assertion is about <i>what {@code OrderSaga} decides to do</i> — which command it
 * issues, which transition it applies — with no transaction manager or event multicaster involved at all.
 * Khorikov's boundary: a test that needs Spring or a database is not a unit test, so it does not live here.
 *
 * <p><b>The {@link Order} aggregate is real, not mocked</b>, exactly as {@code CreateOrderServiceTest} keeps
 * {@code OrderDomainService} real. {@code Order}'s state guards are pure logic with no I/O; mocking them would
 * only let a test pass while the real transition rules were broken. Fixtures come from
 * {@link com.allan.food.order.OrderTestData} and are driven through their own public transition methods
 * ({@code pay()}, {@code approve()}, {@code initCancel()}, {@code cancel()}) to reach the state each scenario
 * needs — the same aggregate the production saga would be handed, just prepared by hand instead of by a prior
 * saga step.
 *
 * <p><b>The clock is fixed</b> so every command's {@code issuedAt} timestamp is asserted exactly rather than
 * merely "not null" — the same reasoning documented on {@code OrderDomainServiceConfiguration.clock()}.
 */
@DisplayName("OrderSaga")
class OrderSagaTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2024-06-01T12:00:00Z");

    private LoadOrderPort loadOrderPort;
    private SaveOrderPort saveOrderPort;
    private PaymentCommandPort paymentCommandPort;
    private RestaurantCommandPort restaurantCommandPort;
    private OrderSaga underTest;

    @BeforeEach
    void setUp() {
        loadOrderPort = mock(LoadOrderPort.class);
        saveOrderPort = mock(SaveOrderPort.class);
        paymentCommandPort = mock(PaymentCommandPort.class);
        restaurantCommandPort = mock(RestaurantCommandPort.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

        underTest = new OrderSaga(loadOrderPort, saveOrderPort, paymentCommandPort, restaurantCommandPort, clock);

        when(saveOrderPort.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ─────────────────────────────────────────────────────────────
    //  Step 1 — onOrderCreated
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("mints a saga id and requests payment for the created order's price")
    void onOrderCreatedRequestsPayment() {
        Order order = pendingOrder();
        OrderCreatedEvent event = OrderCreatedEvent.from(order);

        underTest.onOrderCreated(event);

        ArgumentCaptor<ProcessPayment> captor = ArgumentCaptor.forClass(ProcessPayment.class);
        verify(paymentCommandPort).process(captor.capture());

        ProcessPayment command = captor.getValue();
        assertThat(command.sagaId()).isNotNull();
        assertThat(command.orderId()).isEqualTo(order.orderId());
        assertThat(command.customerId()).isEqualTo(order.customerId());
        assertThat(command.amount()).isEqualByComparingTo(order.price().amount());
        assertThat(command.currency()).isEqualTo(order.price().currency());
        assertThat(command.issuedAt()).isEqualTo(FIXED_INSTANT);
    }

    @Test
    @DisplayName("mints a fresh saga id on every call, never reusing one across orders")
    void onOrderCreatedMintsDistinctSagaIds() {
        underTest.onOrderCreated(OrderCreatedEvent.from(pendingOrder()));
        underTest.onOrderCreated(OrderCreatedEvent.from(pendingOrder()));

        ArgumentCaptor<ProcessPayment> captor = ArgumentCaptor.forClass(ProcessPayment.class);
        verify(paymentCommandPort, org.mockito.Mockito.times(2)).process(captor.capture());

        List<ProcessPayment> commands = captor.getAllValues();
        assertThat(commands.get(0).sagaId()).isNotEqualTo(commands.get(1).sagaId());
    }

    // ─────────────────────────────────────────────────────────────
    //  Step 2 — payment replies
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PaymentCompleted on a PENDING order pays it and requests restaurant approval (the pivot)")
    void onPaymentCompletedAdvancesToApprovalRequest() {
        Order order = pendingOrder();
        when(loadOrderPort.loadById(order.orderId())).thenReturn(Optional.of(order));

        UUID sagaId = UUID.randomUUID();
        underTest.onPaymentCompleted(new PaymentCompleted(sagaId, order.orderId(), UUID.randomUUID(), FIXED_INSTANT));

        assertThat(order.orderStatus()).isEqualTo(com.allan.food.order.domain.model.valueobject.OrderStatus.PAID);
        verify(saveOrderPort).save(order);

        ArgumentCaptor<ApproveOrder> captor = ArgumentCaptor.forClass(ApproveOrder.class);
        verify(restaurantCommandPort).approve(captor.capture());

        ApproveOrder command = captor.getValue();
        assertThat(command.sagaId()).isEqualTo(sagaId);
        assertThat(command.orderId()).isEqualTo(order.orderId());
        assertThat(command.restaurantId()).isEqualTo(order.restaurantId());
        assertThat(command.lines())
                .extracting(ApproveOrder.Line::productId, ApproveOrder.Line::quantity)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ROLEX_ID, 2),
                        org.assertj.core.groups.Tuple.tuple(CHAPATI_ID, 3));
    }

    @Test
    @DisplayName("a duplicate PaymentCompleted for an already-PAID order is absorbed, not re-applied")
    void duplicatePaymentCompletedIsAbsorbed() {
        Order order = paidOrder();
        when(loadOrderPort.loadById(order.orderId())).thenReturn(Optional.of(order));

        underTest.onPaymentCompleted(new PaymentCompleted(
                UUID.randomUUID(), order.orderId(), UUID.randomUUID(), FIXED_INSTANT));

        verifyNoInteractions(saveOrderPort, restaurantCommandPort);
    }

    @Test
    @DisplayName("an anomalous PaymentCompleted for a cancelled order is absorbed without touching the aggregate")
    void anomalousPaymentCompletedIsAbsorbed() {
        Order order = pendingOrder();
        order.cancel(List.of("customer cancelled"));
        when(loadOrderPort.loadById(order.orderId())).thenReturn(Optional.of(order));

        underTest.onPaymentCompleted(new PaymentCompleted(
                UUID.randomUUID(), order.orderId(), UUID.randomUUID(), FIXED_INSTANT));

        verifyNoInteractions(saveOrderPort, restaurantCommandPort);
    }

    @Test
    @DisplayName("a reply for an unknown order id is absorbed as not retriable")
    void replyForUnknownOrderIsAbsorbed() {
        UUID unknownOrderId = UUID.randomUUID();
        when(loadOrderPort.loadById(unknownOrderId)).thenReturn(Optional.empty());

        underTest.onPaymentCompleted(new PaymentCompleted(
                UUID.randomUUID(), unknownOrderId, UUID.randomUUID(), FIXED_INSTANT));

        verifyNoInteractions(saveOrderPort, restaurantCommandPort);
    }

    @Test
    @DisplayName("PaymentFailed on a PENDING order cancels it outright, with no compensation issued")
    void onPaymentFailedCancelsWithNoCompensation() {
        Order order = pendingOrder();
        when(loadOrderPort.loadById(order.orderId())).thenReturn(Optional.of(order));

        underTest.onPaymentFailed(new PaymentFailed(
                UUID.randomUUID(), order.orderId(), List.of("Insufficient funds"), FIXED_INSTANT));

        assertThat(order.orderStatus()).isEqualTo(com.allan.food.order.domain.model.valueobject.OrderStatus.CANCELLED);
        assertThat(order.failureMessages()).containsExactly("Insufficient funds");
        verify(saveOrderPort).save(order);
        verifyNoInteractions(paymentCommandPort, restaurantCommandPort);
    }

    @Test
    @DisplayName("a duplicate PaymentFailed for an already-cancelled order is absorbed")
    void duplicatePaymentFailedIsAbsorbed() {
        Order order = pendingOrder();
        order.cancel(List.of("Insufficient funds"));
        when(loadOrderPort.loadById(order.orderId())).thenReturn(Optional.of(order));

        underTest.onPaymentFailed(new PaymentFailed(
                UUID.randomUUID(), order.orderId(), List.of("Insufficient funds"), FIXED_INSTANT));

        verifyNoInteractions(saveOrderPort);
    }

    @Test
    @DisplayName("PaymentRefunded on a CANCELLING order releases it to CANCELLED with no new failure reasons")
    void onPaymentRefundedReleasesToCancelled() {
        Order order = paidOrder();
        order.initCancel(List.of("Restaurant rejected"));
        when(loadOrderPort.loadById(order.orderId())).thenReturn(Optional.of(order));

        underTest.onPaymentRefunded(new PaymentRefunded(UUID.randomUUID(), order.orderId(), FIXED_INSTANT));

        assertThat(order.orderStatus()).isEqualTo(com.allan.food.order.domain.model.valueobject.OrderStatus.CANCELLED);
        assertThat(order.failureMessages()).containsExactly("Restaurant rejected");
        verify(saveOrderPort).save(order);
    }

    // ─────────────────────────────────────────────────────────────
    //  Step 3 — restaurant replies (the pivot resolves)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("OrderApproved on a PAID order completes the saga")
    void onOrderApprovedCompletesTheSaga() {
        Order order = paidOrder();
        when(loadOrderPort.loadById(order.orderId())).thenReturn(Optional.of(order));

        underTest.onOrderApproved(new OrderApproved(UUID.randomUUID(), order.orderId(), FIXED_INSTANT));

        assertThat(order.orderStatus()).isEqualTo(com.allan.food.order.domain.model.valueobject.OrderStatus.APPROVED);
        verify(saveOrderPort).save(order);
        verifyNoInteractions(paymentCommandPort, restaurantCommandPort);
    }

    @Test
    @DisplayName("OrderRejected on a PAID order locks it into CANCELLING and requests a compensating refund")
    void onOrderRejectedRequestsCompensation() {
        Order order = paidOrder();
        when(loadOrderPort.loadById(order.orderId())).thenReturn(Optional.of(order));

        UUID sagaId = UUID.randomUUID();
        underTest.onOrderRejected(new OrderRejected(
                sagaId, order.orderId(), List.of("Product unavailable"), FIXED_INSTANT));

        assertThat(order.orderStatus())
                .isEqualTo(com.allan.food.order.domain.model.valueobject.OrderStatus.CANCELLING);
        assertThat(order.failureMessages()).containsExactly("Product unavailable");
        verify(saveOrderPort).save(order);

        ArgumentCaptor<RefundPayment> captor = ArgumentCaptor.forClass(RefundPayment.class);
        verify(paymentCommandPort).refund(captor.capture());

        RefundPayment command = captor.getValue();
        assertThat(command.sagaId()).isEqualTo(sagaId);
        assertThat(command.orderId()).isEqualTo(order.orderId());
        assertThat(command.customerId()).isEqualTo(order.customerId());
        assertThat(command.amount()).isEqualByComparingTo(order.price().amount());
        assertThat(command.currency()).isEqualTo(order.price().currency());
        verify(restaurantCommandPort, never()).approve(any());
    }

    @Test
    @DisplayName("a duplicate OrderRejected once compensation is already underway is absorbed")
    void duplicateOrderRejectedIsAbsorbed() {
        Order order = paidOrder();
        order.initCancel(List.of("Product unavailable"));
        when(loadOrderPort.loadById(order.orderId())).thenReturn(Optional.of(order));

        underTest.onOrderRejected(new OrderRejected(
                UUID.randomUUID(), order.orderId(), List.of("Product unavailable"), FIXED_INSTANT));

        verifyNoInteractions(saveOrderPort, paymentCommandPort);
    }
}
