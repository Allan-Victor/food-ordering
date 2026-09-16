package com.allan.food.saga;

import com.allan.food.OrderServiceApplication;
import com.allan.food.order.application.port.in.CreateOrderUseCase;
import com.allan.food.order.application.port.in.command.CreateOrderCommand;
import com.allan.food.order.application.port.in.command.CreateOrderResult;
import com.allan.food.order.application.port.out.LoadOrderPort;
import com.allan.food.order.domain.model.entity.Order;
import com.allan.food.order.domain.model.valueobject.OrderStatus;
import com.allan.food.payment.application.port.out.PaymentPersistencePort;
import com.allan.food.payment.domain.model.CustomerCredit;
import com.allan.food.payment.domain.model.Money;
import com.allan.food.payment.domain.model.Payment;
import com.allan.food.payment.domain.model.PaymentStatus;
import com.allan.food.restaurant.application.port.out.ApprovalPersistencePort;
import com.allan.food.restaurant.domain.model.ApprovalStatus;
import com.allan.food.restaurant.domain.model.OrderApproval;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full saga integration test: {@link CreateOrderUseCase} in, three real bounded contexts (order, payment,
 * restaurant) wired through the genuine {@code @TransactionalEventListener(phase = AFTER_COMMIT)} plumbing, an
 * in-memory H2 database underneath, and no port replaced with a mock or stub. This is the "full saga integration
 * test" layer CLAUDE.md Section 5 asks for, distinct from the orchestration unit tests
 * ({@code OrderSagaTest}, {@code PaymentServiceTest}, {@code RestaurantApprovalServiceTest}) that exercise each
 * component's decisions in isolation with mocked ports.
 *
 * <h2>Why this test class is deliberately <i>not</i> {@code @Transactional}</h2>
 *
 * <p>The instinctive shape for a Spring test — {@code @Transactional} on the test class so every method rolls
 * back and leaves no residue — is precisely wrong here, and understanding why is the whole point of this class
 * existing separately from the unit tests above.
 *
 * <p><b>What you would see elsewhere, and why it fails silently.</b> A {@code @Transactional} test method runs
 * inside one outer transaction that Spring's test framework rolls back at the end, and by default it also makes
 * any transaction opened by code under test <i>join</i> that same outer transaction rather than commit
 * independently. {@code @TransactionalEventListener(phase = AFTER_COMMIT)} — which is the entire mechanism this
 * saga is built on — only ever fires when a transaction actually commits. Wrap the test in one that never
 * commits, and every listener in {@code InProcessSagaReplyAdapter}, the payment context's inbound adapter, and
 * the restaurant context's inbound adapter simply never runs. The test would still pass or fail based on
 * whatever state existed before the (uncommitted) call — silently testing nothing about the wiring it claims to
 * verify. This is the trap the task brief calls out explicitly, and it is a trap precisely because the test does
 * not fail loudly; it passes for the wrong reason.
 *
 * <p><b>The fix used here is the simplest one that is still correct: no transaction at the test boundary at
 * all.</b> Every saga handler already opens its own transaction — {@code OrderSaga.onOrderCreated} and every
 * inbound saga adapter are annotated {@code @Transactional(REQUIRES_NEW)}, exactly so that each step commits
 * independently of whatever called it (see {@code OrderSaga}'s class Javadoc, "Transaction boundaries"). A test
 * method with no surrounding transaction of its own lets each of those calls commit for real, precisely as a
 * production caller (a message listener with no ambient transaction) would experience it. Because this
 * application registers no {@code TaskExecutor} for its event multicaster (grep confirms no {@code @Async} or
 * custom {@code ApplicationEventMulticaster} bean anywhere in {@code src/main}), every {@code AFTER_COMMIT}
 * listener in the chain fires synchronously, on the calling thread, as part of the commit that triggered it. The
 * practical consequence: by the time {@code createOrderUseCase.createOrder(...)} returns to this test method,
 * the entire saga — payment, the pivot, and any compensation — has already run to its terminal state. There is
 * no {@code TestTransaction.flagForCommit()}/{@code end()} dance to perform, because nothing here needs rescuing
 * from a rollback that was never going to happen. Reaching for that API would in fact be the wrong move: it
 * exists for a test that starts inside a transactional context and needs to force a real commit out of it, which
 * is not this test's starting shape and would only add ceremony.
 *
 * <p><b>{@code @ActiveProfiles("test")}</b> keeps {@code PaymentSeedConfiguration}'s {@code @Profile("dev")}
 * {@code ApplicationRunner} — which seeds two fixed customer ids on startup — out of this run, so every test
 * method controls its own customer's balance from a known, empty starting point rather than inheriting seed
 * data tuned for manual exploration.
 *
 * <h2>Why these three scenarios, and where their forks live in the real code</h2>
 *
 * <p>All three orders are placed against the seeded {@code ACTIVE_RESTAURANT_ID}
 * ({@code InMemoryRestaurantAdapter} / {@code InMemoryAvailabilityAdapter}), which is deliberately the one point
 * where the order context's menu replica and the restaurant context's availability seed share ids — Rolex and
 * Matoke are on the menu <i>and</i> currently preparable; Chapati is on the menu but marked unavailable in the
 * restaurant context, which is the seeded fork this test drives the compensation path through rather than
 * fabricating one.
 */
@SpringBootTest(classes = OrderServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("Order saga (full integration)")
class OrderSagaIntegrationTest {

    private static final UUID ACTIVE_RESTAURANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROLEX_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CHAPATI_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Autowired
    private CreateOrderUseCase createOrderUseCase;

    @Autowired
    private LoadOrderPort loadOrderPort;

    @Autowired
    private PaymentPersistencePort paymentPersistencePort;

    @Autowired
    private ApprovalPersistencePort approvalPersistencePort;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Reads a saga participant's aggregate back through a short, dedicated read-only transaction.
     *
     * <p><b>Why a read needs a transaction here when nothing else in this test does.</b> {@code Order}'s items
     * and failure messages, and {@code OrderApproval}'s rejection reasons, are all mapped as lazy JPA
     * collections, and none of the persistence adapters involved ({@code OrderPersistenceAdapter},
     * {@code ApprovalPersistenceAdapter}) are themselves {@code @Transactional} — deliberately, per
     * {@code OrderPersistenceAdapter}'s own Javadoc, because in production the surrounding use case always
     * supplies the session. This test method supplies none, on purpose, so that the saga's own commits are the
     * real, independent commits described in this class's Javadoc. That leaves exactly one gap: reading a lazy
     * collection back out afterwards needs a session of its own. Opening one short-lived, read-only transaction
     * purely to materialise the result is the correct fix — it happens well after the saga has already run to
     * completion and commits nothing, so it cannot mask a wiring failure the way wrapping the whole test method
     * in a transaction would (see this class's main Javadoc for why that alternative is actively wrong here).
     */
    private <T> T inReadOnlyTransaction(java.util.function.Supplier<T> read) {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        return readOnly.execute(status -> read.get());
    }

    private Order loadOrder(UUID trackingId) {
        return inReadOnlyTransaction(() -> loadOrderPort.loadByTrackingId(trackingId).orElseThrow());
    }

    private OrderApproval loadApproval(UUID orderId) {
        return inReadOnlyTransaction(() -> approvalPersistencePort.findApprovalByOrderId(orderId).orElseThrow());
    }

    private static CreateOrderCommand orderFor(UUID customerId, UUID productId) {
        return new CreateOrderCommand(
                customerId,
                ACTIVE_RESTAURANT_ID,
                List.of(new CreateOrderCommand.OrderItemDto(productId, 1)),
                new CreateOrderCommand.OrderAddressDto("Plot 12 Kira Road", "256", "Kampala"));
    }

    private void seedCredit(UUID customerId, String balance) {
        paymentPersistencePort.saveCredit(CustomerCredit.open(customerId, Money.of(balance, "UGX")));
    }

    // ─────────────────────────────────────────────────────────────
    //  Happy path: order → payment approved → restaurant approved
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("happy path: a funded customer ordering an available product ends APPROVED")
    void happyPathReachesApproved() {
        UUID customerId = UUID.randomUUID();
        seedCredit(customerId, "50000");

        CreateOrderResult result = createOrderUseCase.createOrder(orderFor(customerId, ROLEX_ID));

        Order order = loadOrder(result.trackingId());
        assertThat(order.orderStatus()).isEqualTo(OrderStatus.APPROVED);
        assertThat(order.failureMessages()).isEmpty();

        Payment payment = paymentPersistencePort.findPaymentByOrderId(order.orderId()).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);

        OrderApproval approval = loadApproval(order.orderId());
        assertThat(approval.status()).isEqualTo(ApprovalStatus.APPROVED);

        CustomerCredit credit = paymentPersistencePort.findCredit(customerId).orElseThrow();
        assertThat(credit.balance()).isEqualTo(Money.of("42000", "UGX")); // 50000 - 8000 (Rolex)
    }

    // ─────────────────────────────────────────────────────────────
    //  Compensation path: payment succeeds, restaurant rejects, refund
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("compensation path: restaurant rejection after a successful charge refunds and cancels")
    void compensationPathRefundsAndCancels() {
        UUID customerId = UUID.randomUUID();
        seedCredit(customerId, "50000");

        // Chapati is on the order context's menu replica but seeded unavailable in the restaurant
        // context — the pivot rejects it, which is exactly what makes compensation observable.
        CreateOrderResult result = createOrderUseCase.createOrder(orderFor(customerId, CHAPATI_ID));

        Order order = loadOrder(result.trackingId());
        assertThat(order.orderStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.failureMessages())
                .anySatisfy(reason -> assertThat(reason).contains("unavailable"));

        Payment payment = paymentPersistencePort.findPaymentByOrderId(order.orderId()).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED);

        OrderApproval approval = loadApproval(order.orderId());
        assertThat(approval.status()).isEqualTo(ApprovalStatus.REJECTED);

        // The refund is only real if the balance actually came back.
        CustomerCredit credit = paymentPersistencePort.findCredit(customerId).orElseThrow();
        assertThat(credit.balance()).isEqualTo(Money.of("50000", "UGX"));
    }

    // ─────────────────────────────────────────────────────────────
    //  Early failure path: payment declined before the restaurant is ever contacted
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("early failure path: an unaffordable order is cancelled without the restaurant ever being asked")
    void earlyFailurePathCancelsBeforeThePivot() {
        UUID customerId = UUID.randomUUID();
        // Deliberately not seeded: PaymentService opens a zero balance for an unknown customer,
        // which cannot afford any positively-priced order — see PaymentServiceTest's equivalent
        // unit-level case for the same rule asserted without the database.

        CreateOrderResult result = createOrderUseCase.createOrder(orderFor(customerId, ROLEX_ID));

        Order order = loadOrder(result.trackingId());
        assertThat(order.orderStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.failureMessages())
                .anySatisfy(reason -> assertThat(reason).contains("Insufficient funds"));

        Payment payment = paymentPersistencePort.findPaymentByOrderId(order.orderId()).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);

        // The pivot never ran: payment failed before the compensatable step even succeeded, so no
        // ApproveOrder command was ever issued to the restaurant context.
        Optional<OrderApproval> approval = approvalPersistencePort.findApprovalByOrderId(order.orderId());
        assertThat(approval).isEmpty();
    }
}
