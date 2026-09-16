package com.allan.food.payment.application.service;

import com.allan.food.payment.application.port.out.PaymentPersistencePort;
import com.allan.food.payment.application.port.out.PaymentReplyPort;
import com.allan.food.payment.domain.model.CustomerCredit;
import com.allan.food.payment.domain.model.Money;
import com.allan.food.payment.domain.model.Payment;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Orchestration unit tests for {@link PaymentService}: plain JUnit and Mockito, no Spring context, no database
 * — the same discipline applied to {@code OrderSagaTest}. Every port is mocked; {@link Payment} and
 * {@link CustomerCredit} are exercised as real domain objects because their guards are pure logic, and mocking
 * them would hide exactly the invariants (balance never negative, only a {@code COMPLETED} payment can be
 * refunded) this participant leans on.
 *
 * <p>Currency is UGX throughout, per CLAUDE.md's test-currency convention: zero minor units, so a hardcoded
 * scale of 2 would be caught rather than passing by accident.
 */
@DisplayName("PaymentService")
class PaymentServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2024-06-01T12:00:00Z");
    private static final String UGX = "UGX";

    private static final UUID CUSTOMER_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final UUID ORDER_ID = UUID.fromString("dddddddd-0000-0000-0000-000000000001");

    private PaymentPersistencePort persistence;
    private PaymentReplyPort replyPort;
    private PaymentService underTest;

    @BeforeEach
    void setUp() {
        persistence = mock(PaymentPersistencePort.class);
        replyPort = mock(PaymentReplyPort.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

        underTest = new PaymentService(persistence, replyPort, clock);

        when(persistence.savePayment(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.saveCredit(any(CustomerCredit.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static ProcessPayment processPayment(String amount) {
        return new ProcessPayment(UUID.randomUUID(), ORDER_ID, CUSTOMER_ID,
                new java.math.BigDecimal(amount), UGX, FIXED_INSTANT);
    }

    // ─────────────────────────────────────────────────────────────
    //  onProcessPayment
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an affordable charge debits the balance, records a completed payment, and replies completed")
    void affordableChargeCompletes() {
        when(persistence.findPaymentByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(persistence.findCredit(CUSTOMER_ID))
                .thenReturn(Optional.of(CustomerCredit.open(CUSTOMER_ID, Money.of("50000", UGX))));

        underTest.onProcessPayment(processPayment("8000"));

        ArgumentCaptor<CustomerCredit> creditCaptor = ArgumentCaptor.forClass(CustomerCredit.class);
        verify(persistence).saveCredit(creditCaptor.capture());
        assertThat(creditCaptor.getValue().balance()).isEqualTo(Money.of("42000", UGX));

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(persistence).savePayment(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().status()).isEqualTo(com.allan.food.payment.domain.model.PaymentStatus.COMPLETED);
        assertThat(paymentCaptor.getValue().orderId()).isEqualTo(ORDER_ID);

        ArgumentCaptor<PaymentCompleted> replyCaptor = ArgumentCaptor.forClass(PaymentCompleted.class);
        verify(replyPort).completed(replyCaptor.capture());
        assertThat(replyCaptor.getValue().orderId()).isEqualTo(ORDER_ID);
        assertThat(replyCaptor.getValue().paymentId()).isEqualTo(paymentCaptor.getValue().paymentId());
    }

    @Test
    @DisplayName("no existing credit record opens one at zero, which an order can never afford")
    void noCreditRecordOpensAtZeroAndFails() {
        when(persistence.findPaymentByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(persistence.findCredit(CUSTOMER_ID)).thenReturn(Optional.empty());

        underTest.onProcessPayment(processPayment("8000"));

        verify(persistence, never()).saveCredit(any());
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(persistence).savePayment(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().status()).isEqualTo(com.allan.food.payment.domain.model.PaymentStatus.FAILED);

        verify(replyPort).failed(any(PaymentFailed.class));
        verify(replyPort, never()).completed(any());
    }

    @Test
    @DisplayName("an unaffordable charge fails as an ordinary reply, not an exception, and touches no balance")
    void unaffordableChargeFailsWithoutTouchingBalance() {
        when(persistence.findPaymentByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(persistence.findCredit(CUSTOMER_ID))
                .thenReturn(Optional.of(CustomerCredit.open(CUSTOMER_ID, Money.of("1000", UGX))));

        underTest.onProcessPayment(processPayment("8000"));

        verify(persistence, never()).saveCredit(any());

        ArgumentCaptor<PaymentFailed> replyCaptor = ArgumentCaptor.forClass(PaymentFailed.class);
        verify(replyPort).failed(replyCaptor.capture());
        assertThat(replyCaptor.getValue().reasons()).anySatisfy(
                reason -> assertThat(reason).contains("Insufficient funds"));
        verify(replyPort, never()).completed(any());
    }

    @Test
    @DisplayName("a duplicate ProcessPayment for an already-completed payment re-sends the completion, without re-charging")
    void duplicateProcessPaymentReplaysCompletion() {
        Payment existing = Payment.completed(ORDER_ID, CUSTOMER_ID, Money.of("8000", UGX), FIXED_INSTANT);
        when(persistence.findPaymentByOrderId(ORDER_ID)).thenReturn(Optional.of(existing));

        underTest.onProcessPayment(processPayment("8000"));

        ArgumentCaptor<PaymentCompleted> replyCaptor = ArgumentCaptor.forClass(PaymentCompleted.class);
        verify(replyPort).completed(replyCaptor.capture());
        assertThat(replyCaptor.getValue().paymentId()).isEqualTo(existing.paymentId());
        verify(persistence, never()).findCredit(any());
        verify(persistence, never()).saveCredit(any());
    }

    @Test
    @DisplayName("a duplicate ProcessPayment for a previously-refunded payment still replies completed")
    void duplicateProcessPaymentAfterRefundStillReplaysCompletion() {
        Payment refunded = Payment.completed(ORDER_ID, CUSTOMER_ID, Money.of("8000", UGX), FIXED_INSTANT);
        refunded.markRefunded();
        when(persistence.findPaymentByOrderId(ORDER_ID)).thenReturn(Optional.of(refunded));

        underTest.onProcessPayment(processPayment("8000"));

        verify(replyPort).completed(any(PaymentCompleted.class));
        verify(persistence, never()).findCredit(any());
    }

    @Test
    @DisplayName("a duplicate ProcessPayment for a previously-failed payment re-sends the failure")
    void duplicateProcessPaymentReplaysFailure() {
        Payment failed = Payment.failed(ORDER_ID, CUSTOMER_ID, Money.of("8000", UGX), FIXED_INSTANT);
        when(persistence.findPaymentByOrderId(ORDER_ID)).thenReturn(Optional.of(failed));

        underTest.onProcessPayment(processPayment("8000"));

        verify(replyPort).failed(any(PaymentFailed.class));
        verify(persistence, never()).findCredit(any());
    }

    // ─────────────────────────────────────────────────────────────
    //  onRefundPayment
    // ─────────────────────────────────────────────────────────────

    private static RefundPayment refundPayment() {
        return new RefundPayment(UUID.randomUUID(), ORDER_ID, CUSTOMER_ID,
                new java.math.BigDecimal("8000"), UGX, FIXED_INSTANT);
    }

    @Test
    @DisplayName("refunding a completed payment restores the balance and replies refunded")
    void refundRestoresBalance() {
        Payment completed = Payment.completed(ORDER_ID, CUSTOMER_ID, Money.of("8000", UGX), FIXED_INSTANT);
        when(persistence.findPaymentByOrderId(ORDER_ID)).thenReturn(Optional.of(completed));
        when(persistence.findCredit(CUSTOMER_ID))
                .thenReturn(Optional.of(CustomerCredit.open(CUSTOMER_ID, Money.of("42000", UGX))));

        underTest.onRefundPayment(refundPayment());

        ArgumentCaptor<CustomerCredit> creditCaptor = ArgumentCaptor.forClass(CustomerCredit.class);
        verify(persistence).saveCredit(creditCaptor.capture());
        assertThat(creditCaptor.getValue().balance()).isEqualTo(Money.of("50000", UGX));

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(persistence).savePayment(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().status()).isEqualTo(com.allan.food.payment.domain.model.PaymentStatus.REFUNDED);

        verify(replyPort).refunded(any(PaymentRefunded.class));
    }

    @Test
    @DisplayName("refunding an order with no payment record still replies, so the saga is not left stranded")
    void refundWithNoPaymentRecordStillReplies() {
        when(persistence.findPaymentByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        underTest.onRefundPayment(refundPayment());

        verify(replyPort).refunded(any(PaymentRefunded.class));
        verify(persistence, never()).saveCredit(any());
        verify(persistence, never()).savePayment(any());
    }

    @Test
    @DisplayName("a duplicate refund for an already-refunded payment re-sends the reply without double-crediting")
    void duplicateRefundIsAbsorbed() {
        Payment refunded = Payment.completed(ORDER_ID, CUSTOMER_ID, Money.of("8000", UGX), FIXED_INSTANT);
        refunded.markRefunded();
        when(persistence.findPaymentByOrderId(ORDER_ID)).thenReturn(Optional.of(refunded));

        underTest.onRefundPayment(refundPayment());

        verify(replyPort).refunded(any(PaymentRefunded.class));
        verify(persistence, never()).findCredit(any());
        verify(persistence, never()).saveCredit(any());
    }

    @Test
    @DisplayName("refunding a payment that never completed replies rather than hanging the saga")
    void refundOfFailedPaymentStillReplies() {
        Payment failed = Payment.failed(ORDER_ID, CUSTOMER_ID, Money.of("8000", UGX), FIXED_INSTANT);
        when(persistence.findPaymentByOrderId(ORDER_ID)).thenReturn(Optional.of(failed));

        underTest.onRefundPayment(refundPayment());

        verify(replyPort).refunded(any(PaymentRefunded.class));
        verify(persistence, never()).findCredit(any());
    }

    @Test
    @DisplayName("a completed payment with no matching credit record is a genuine fault, not an absorbed reply")
    void refundWithMissingCreditRecordThrows() {
        Payment completed = Payment.completed(ORDER_ID, CUSTOMER_ID, Money.of("8000", UGX), FIXED_INSTANT);
        when(persistence.findPaymentByOrderId(ORDER_ID)).thenReturn(Optional.of(completed));
        when(persistence.findCredit(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> underTest.onRefundPayment(refundPayment()));

        verifyNoInteractions(replyPort);
    }
}
