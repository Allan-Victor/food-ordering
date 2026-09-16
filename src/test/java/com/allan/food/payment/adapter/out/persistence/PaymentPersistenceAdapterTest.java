package com.allan.food.payment.adapter.out.persistence;
// Same package as the adapter, the mapper and the entities — all package-private by design.

import com.allan.food.payment.domain.model.CustomerCredit;
import com.allan.food.payment.domain.model.Money;
import com.allan.food.payment.domain.model.Payment;
import com.allan.food.payment.domain.model.PaymentStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Persistence adapter tests against a real (in-memory) database, mirroring
 * {@code OrderPersistenceAdapterTest}'s shape and rationale — see that class for the argument for
 * {@code @DataJpaTest} over mocking the repository.
 *
 * <p><b>Why this class existed as a gap worth closing.</b> {@code CustomerCreditJpaEntity}'s own Javadoc says the
 * version "matters more on this table than on any other in the system," and {@link CustomerCredit}'s says the
 * lost-update race here is "more obvious" than {@code Order}'s: two orders from the same customer paid
 * concurrently both read the same balance, both compute a new one, and without the optimistic lock the second
 * write silently discards the first — with real money. {@code OrderPersistenceAdapterTest} exercises exactly
 * this shape for {@code Order} ({@code staleVersionIsRejected}); nothing exercised it for {@code CustomerCredit}
 * before this class, despite it being the aggregate the codebase's own comments call out as the sharper case.
 */
@DataJpaTest
@Import(PaymentPersistenceAdapter.class)
@DisplayName("PaymentPersistenceAdapter")
class PaymentPersistenceAdapterTest {

    @Autowired
    private PaymentPersistenceAdapter underTest;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("a payment round-trips without losing or altering anything")
    void roundTripsPaymentFaithfully() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Payment original = Payment.completed(orderId, customerId, Money.of("22000", "UGX"), createdAt);

        underTest.savePayment(original);
        flushAndClear();

        Payment loaded = underTest.findPaymentByOrderId(orderId).orElseThrow();

        assertThat(loaded.paymentId()).isEqualTo(original.paymentId());
        assertThat(loaded.orderId()).isEqualTo(orderId);
        assertThat(loaded.customerId()).isEqualTo(customerId);
        assertThat(loaded.amount()).isEqualTo(Money.of("22000", "UGX"));
        assertThat(loaded.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(loaded.createdAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("a zero-decimal currency survives the fixed scale-4 column")
    void preservesCurrencyScaleOnPayment() {
        UUID orderId = UUID.randomUUID();
        Payment original = Payment.completed(orderId, UUID.randomUUID(), Money.of("22000", "UGX"), Instant.now());

        underTest.savePayment(original);
        flushAndClear();

        Payment loaded = underTest.findPaymentByOrderId(orderId).orElseThrow();

        // Stored as 22000.0000 in a scale-4 column; Money's constructor rescales to UGX's zero
        // fraction digits on the way back. A hardcoded scale of 2 would return 22000.00 here.
        assertThat(loaded.amount().amount().scale()).isZero();
    }

    @Test
    @DisplayName("a new payment inserts at version zero and comes back not-new")
    void insertAssignsPaymentVersionZero() {
        Payment saved = underTest.savePayment(
                Payment.completed(UUID.randomUUID(), UUID.randomUUID(), Money.of("1000", "UGX"), Instant.now()));

        assertThat(saved.version()).isZero();
        assertThat(saved.isNew()).isFalse();
    }

    @Test
    @DisplayName("a second write increments the payment version")
    void updateIncrementsPaymentVersion() {
        Payment saved = underTest.savePayment(
                Payment.completed(UUID.randomUUID(), UUID.randomUUID(), Money.of("1000", "UGX"), Instant.now()));
        saved.markRefunded();

        Payment updated = underTest.savePayment(saved);

        assertThat(updated.version()).isEqualTo(1L);
        assertThat(updated.status()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    @DisplayName("the order-id uniqueness constraint rejects a second payment for the same order")
    void orderIdUniquenessIsEnforced() {
        UUID orderId = UUID.randomUUID();
        underTest.savePayment(Payment.completed(orderId, UUID.randomUUID(), Money.of("1000", "UGX"), Instant.now()));
        flushAndClear();

        // The idempotency guarantee argued for in PaymentJpaEntity's Javadoc: a second insert for the
        // same order id — not an update of the first — must fail rather than silently succeed.
        assertThatExceptionOfType(org.springframework.dao.DataIntegrityViolationException.class)
                .isThrownBy(() -> underTest.savePayment(
                        Payment.completed(orderId, UUID.randomUUID(), Money.of("2000", "UGX"), Instant.now())));
    }

    @Test
    @DisplayName("an unknown order id yields empty rather than throwing")
    void unknownOrderIdIsEmpty() {
        assertThat(underTest.findPaymentByOrderId(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("a credit balance round-trips without losing or altering anything")
    void roundTripsCreditFaithfully() {
        UUID customerId = UUID.randomUUID();
        CustomerCredit original = CustomerCredit.open(customerId, Money.of("50000", "UGX"));

        underTest.saveCredit(original);
        flushAndClear();

        CustomerCredit loaded = underTest.findCredit(customerId).orElseThrow();

        assertThat(loaded.customerId()).isEqualTo(customerId);
        assertThat(loaded.balance()).isEqualTo(Money.of("50000", "UGX"));
    }

    @Test
    @DisplayName("a new credit record inserts at version zero and comes back not-new")
    void insertAssignsCreditVersionZero() {
        CustomerCredit saved = underTest.saveCredit(
                CustomerCredit.open(UUID.randomUUID(), Money.of("50000", "UGX")));

        assertThat(saved.version()).isZero();
        assertThat(saved.isNew()).isFalse();
    }

    @Test
    @DisplayName("a second write increments the credit version")
    void updateIncrementsCreditVersion() {
        CustomerCredit saved = underTest.saveCredit(
                CustomerCredit.open(UUID.randomUUID(), Money.of("50000", "UGX")));
        saved.debit(Money.of("10000", "UGX"));

        CustomerCredit updated = underTest.saveCredit(saved);

        assertThat(updated.version()).isEqualTo(1L);
        assertThat(updated.balance()).isEqualTo(Money.of("40000", "UGX"));
    }

    /**
     * The centerpiece of this class: the "textbook lost update, with real money" that both
     * {@code CustomerCreditJpaEntity} and {@link CustomerCredit}'s class Javadoc warn about by name.
     *
     * <p>Two payment handlers — two orders from the same customer settling concurrently — each load the same
     * balance, each debit it independently, and each try to save. Without the {@code @Version} column both
     * writes would succeed and the second would silently erase the first debit, understating how much the
     * customer has actually spent. With it, the second writer is told its read is stale rather than allowed to
     * overwrite blind — exactly the guarantee {@code staleVersionIsRejected} proves for {@code Order}, proved
     * here for the aggregate whose own comments call this race the more obvious one.
     */
    @Test
    @DisplayName("a concurrent debit against a stale balance version is rejected — the lost-update guard")
    void staleCreditVersionIsRejected() {
        UUID customerId = UUID.randomUUID();
        underTest.saveCredit(CustomerCredit.open(customerId, Money.of("50000", "UGX")));
        flushAndClear();

        // Two handlers load the same balance — exactly what two concurrently-settling orders produce.
        CustomerCredit first = underTest.findCredit(customerId).orElseThrow();
        CustomerCredit second = underTest.findCredit(customerId).orElseThrow();

        first.debit(Money.of("10000", "UGX"));
        underTest.saveCredit(first);

        second.debit(Money.of("5000", "UGX"));

        // Without the version, this would silently overwrite and the customer would appear to have
        // 45000 left rather than the correct 35000. With it, the loser is told.
        assertThatExceptionOfType(org.springframework.dao.OptimisticLockingFailureException.class)
                .isThrownBy(() -> underTest.saveCredit(second));
    }

    /**
     * Forces the pending SQL out and detaches everything, so the subsequent load genuinely reads the database
     * rather than returning the same instance from the persistence context. See
     * {@code OrderPersistenceAdapterTest.flushAndClear} for the same argument.
     */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
