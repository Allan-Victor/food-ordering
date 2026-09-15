package com.allan.food.order.adapter.out.persistence;
// Same package as the adapter, the mapper and the entities — all package-private by design.

import com.allan.food.order.domain.model.entity.Order;
import com.allan.food.order.domain.model.entity.OrderItem;
import com.allan.food.order.domain.model.valueobject.OrderStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static com.allan.food.order.OrderTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Persistence adapter tests against a real (in-memory) database.
 *
 * <p><b>{@code @DataJpaTest}, not {@code @SpringBootTest}.</b> The slice starts JPA, the repositories and a
 * transaction manager, and nothing else — no web layer, no our own services. Faster, and a failure here can
 * only mean the persistence adapter is wrong.
 *
 * <p><b>{@code @Import} is required</b> because {@code @DataJpaTest} scans for {@code @Entity} and Spring Data
 * repositories, not for {@code @Component}. The adapter is a component, so it must be imported explicitly.
 *
 * <p><b>Why a real database instead of mocking the repository.</b> Everything worth testing here is exactly
 * what a mock would fake: does the schema accept our types, does an {@code @ElementCollection} round-trip,
 * does {@code save} insert or merge. H2 is honest enough for those, and Slice 4's Testcontainers Postgres will
 * check the dialect-specific remainder.
 *
 * <p>Each test method is transactional and rolled back by default, so no cleanup and no inter-test leakage.
 */
@DataJpaTest
@Import(OrderPersistenceAdapter.class)
@DisplayName("OrderPersistenceAdapter")
class OrderPersistenceAdapterTest {

    @Autowired
    private OrderPersistenceAdapter underTest;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("round-trips an aggregate without losing or altering anything")
    void roundTripsFaithfully() {
        Order original = pendingOrder();

        underTest.save(original);
        flushAndClear();

        Order loaded = underTest.loadByTrackingId(original.trackingId()).orElseThrow();

        assertThat(loaded.orderId()).isEqualTo(original.orderId());
        assertThat(loaded.customerId()).isEqualTo(original.customerId());
        assertThat(loaded.restaurantId()).isEqualTo(original.restaurantId());
        assertThat(loaded.trackingId()).isEqualTo(original.trackingId());
        assertThat(loaded.orderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(loaded.deliveryAddress()).isEqualTo(original.deliveryAddress());
        assertThat(loaded.price()).isEqualTo(original.price());
        assertThat(loaded.items()).hasSameSizeAs(original.items());
    }

    @Test
    @DisplayName("a zero-decimal currency survives the fixed scale-4 column")
    void preservesCurrencyScale() {
        Order original = pendingOrder();

        underTest.save(original);
        flushAndClear();

        Order loaded = underTest.loadByTrackingId(original.trackingId()).orElseThrow();

        // Stored as 22000.0000 in a scale-4 column; Money's constructor rescales to UGX's zero
        // fraction digits on the way back. A hardcoded scale of 2 would return 22000.00 here.
        assertThat(loaded.price()).isEqualTo(ugx("22000"));
        assertThat(loaded.price().amount().scale()).isZero();
    }

    @Test
    @DisplayName("restores item positions in order, whatever order the database returns rows in")
    void restoresItemOrdering() {
        Order original = pendingOrder();

        underTest.save(original);
        flushAndClear();

        Order loaded = underTest.loadByTrackingId(original.trackingId()).orElseThrow();

        // @ElementCollection guarantees no ordering. The mapper sorts by the stored position,
        // which is why this passes rather than passing by luck.
        assertThat(loaded.items())
                .extracting(OrderItem::position, OrderItem::productName, OrderItem::quantity)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "Rolex", 2),
                        org.assertj.core.groups.Tuple.tuple(2, "Chapati", 3));
    }

    @Test
    @DisplayName("derived subtotals come back consistent with the stored price and quantity")
    void recomputesSubTotalsOnLoad() {
        Order original = pendingOrder();

        underTest.save(original);
        flushAndClear();

        Order loaded = underTest.loadByTrackingId(original.trackingId()).orElseThrow();

        assertThat(loaded.items())
                .extracting(OrderItem::subTotal)
                .containsExactly(ugx("16000"), ugx("6000"));
    }

    @Test
    @DisplayName("failure messages persist as rows, preserving order and content")
    void persistsFailureMessagesAsRows() {
        Order order = paidOrder();
        order.initCancel(List.of("payment provider timed out"));
        order.cancel(List.of("payment, reversed; comma and semicolon inside"));

        underTest.save(order);
        flushAndClear();

        Order loaded = underTest.loadByTrackingId(order.trackingId()).orElseThrow();

        // A delimiter-joined column would corrupt the second message. One row per message cannot.
        assertThat(loaded.failureMessages()).containsExactly(
                "payment provider timed out",
                "payment, reversed; comma and semicolon inside");
        assertThat(loaded.orderStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("a new aggregate inserts at version zero and comes back not-new")
    void insertAssignsVersionZero() {
        Order saved = underTest.save(pendingOrder());

        // The domain's -1 sentinel became null on the way in, which is how Spring Data knew to
        // insert rather than merge; the provider's 0 came back out through reconstitute.
        assertThat(saved.version()).isZero();
        assertThat(saved.isNew()).isFalse();
    }

    @Test
    @DisplayName("a second write increments the version")
    void updateIncrementsVersion() {
        Order saved = underTest.save(pendingOrder());
        saved.pay();

        Order updated = underTest.save(saved);

        assertThat(updated.version()).isEqualTo(1L);
        assertThat(updated.orderStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("a concurrent write against a stale version is rejected — the saga's lost-update guard")
    void staleVersionIsRejected() {
        Order saved = underTest.save(pendingOrder());
        flushAndClear();

        // Two handlers load the same order — exactly what duplicate delivery produces.
        Order first = underTest.loadByTrackingId(saved.trackingId()).orElseThrow();
        Order second = underTest.loadByTrackingId(saved.trackingId()).orElseThrow();

        first.pay();
        underTest.save(first);

        second.pay();

        // Without the version, this would silently overwrite. With it, the loser is told.
        assertThatExceptionOfType(org.springframework.dao.OptimisticLockingFailureException.class)
                .isThrownBy(() -> underTest.save(second));
    }

    @Test
    @DisplayName("an unknown tracking id yields empty rather than throwing")
    void unknownTrackingIdIsEmpty() {
        assertThat(underTest.loadByTrackingId(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("status is stored as text, so the value in the database is readable and reorder-proof")
    void storesStatusAsString() {
        Order order = paidOrder();
        underTest.save(order);
        flushAndClear();

        Object stored = entityManager
                .createNativeQuery("select order_status from orders where id = :id")
                .setParameter("id", order.orderId())
                .getSingleResult();

        // EnumType.ORDINAL would store 1 here, and reordering the enum would silently
        // reinterpret every existing row.
        assertThat(stored).hasToString("PAID");
    }

    /**
     * Forces the pending SQL out and detaches everything, so the subsequent load genuinely reads the database
     * rather than returning the same instance from the persistence context. Without this, most of the
     * assertions above would pass even if the mapping were broken.
     */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}