package com.allan.food.order.domain.model.entity;

import com.allan.food.order.domain.exception.OrderDomainException;
import com.allan.food.order.domain.model.valueobject.OrderStatus;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.allan.food.order.OrderTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@DisplayName("Order aggregate")
class OrderTest {

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("derives the total as the sum of the lines")
        void totalIsSumOfLines() {
            // 2 * 8000 + 3 * 2000. Note what cannot be tested here: passing a wrong total
            assertThat(pendingOrder().price()).isEqualTo(ugx("22000"));
        }

        @Test
        @DisplayName("assigns sequential positions scoped to the order")
        void assignPositionsFromOne() {
            assertThat(pendingOrder().items())
                    .extracting(OrderItem::position)
                    .containsExactly(1, 2);
        }

        @Test
        void startsPendingWithNoFailures() {
            Order order = pendingOrder();

            assertThat(order.orderStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(order.failureMessages()).isEmpty();
        }

        @Test
        @DisplayName("mints distinct order and tracking identities")
        void mintsTwoDistinctIds() {
            Order order = pendingOrder();

            // Both are generated internally, so a test can assert their relationship but never
            // their value. Conflating the internal id with the public tracking id would leak
            // persistence identity to customers; this pins that they are separate.
            assertThat(order.orderId()).isNotNull();
            assertThat(order.trackingId()).isNotNull().isNotEqualTo(order.orderId());
        }

        @Test
        void carriesThroughConfirmedNamesAndPrices() {
            assertThat(pendingOrder().items())
                    .extracting(OrderItem::productName, OrderItem::quantity, OrderItem::subTotal)
                    .containsExactly(Tuple.tuple("Rolex", 2, ugx("16000")),
                            Tuple.tuple("Chapati", 3, ugx("6000")));
        }

        @Test
        void rejectsMissingRequiredParts() {
            assertThatExceptionOfType(OrderDomainException.class)
                    .isThrownBy(() -> Order.create(null, RESTAURANT_ID, address(), confirmedItems()));

            assertThatExceptionOfType(OrderDomainException.class)
                    .isThrownBy(()-> Order.create(CUSTOMER_ID, null, address(), confirmedItems()));

            assertThatExceptionOfType(OrderDomainException.class)
                    .isThrownBy(()-> Order.create(CUSTOMER_ID, RESTAURANT_ID, null, confirmedItems()));

        }

        @Test
        void rejectsAnEmptyOrder() {
            assertThatExceptionOfType(OrderDomainException.class)
                    .isThrownBy(()-> Order.create(CUSTOMER_ID, RESTAURANT_ID, address(), List.of()))
                    .withMessageContaining("at least one item");

            assertThatExceptionOfType(OrderDomainException.class)
                    .isThrownBy(()-> Order.create(CUSTOMER_ID, RESTAURANT_ID,address(),null));
        }
    }

    @Nested
    @DisplayName("state transitions")
    class Transitions {

        @Test
        void pendingToPaidToApproved() {
            Order order = pendingOrder();

            order.pay();
            assertThat(order.orderStatus()).isEqualTo(OrderStatus.PAID);

            order.approve();
            assertThat(order.orderStatus()).isEqualTo(OrderStatus.APPROVED);
        }

        @Test
        @DisplayName("a repeated pay is rejected — the guard is the saga's idempotency mechanism")
        void payIsNotIdempotentlySilent() {
            Order order = paidOrder();

            // At-least-once delivery means a duplicate payment message will arrive. The aggregate
            // must reject it loudly rather than transition twice.
            assertThatExceptionOfType(OrderDomainException.class).isThrownBy(order::pay);
        }

        @Test
        void approveRequiresPaid() {
            assertThatExceptionOfType(OrderDomainException.class)
                    .isThrownBy(() -> pendingOrder().approve())
                    .withMessageContaining("PENDING");
        }

        @Test
        @DisplayName("two-phase cancel: PAID → CANCELLING → CANCELLED")
        void twoPhaseCancelPassesThroughCancelling() {
            Order order = paidOrder();

            order.initCancel(List.of("payment provider timed out"));

            // The intermediate state is the semantic lock: compensation is in flight and the order
            // must not be treated as either live or finished.
            assertThat(order.orderStatus()).isEqualTo(OrderStatus.CANCELLING);

            order.cancel(List.of("payment rolled back"));
            assertThat(order.orderStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("cancel direct from PENDING, when payment never happened")
        void cancelFromPending() {
            Order order = pendingOrder();

            order.cancel(List.of("customer changed their mind"));

            assertThat(order.orderStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        void initCancelRequiresPaid() {
            assertThatExceptionOfType(OrderDomainException.class)
                    .isThrownBy(() -> pendingOrder().initCancel(List.of("too early")));
        }

        @Test
        void cancelRejectedFromApproved() {
            Order order = paidOrder();
            order.approve();

            assertThatExceptionOfType(OrderDomainException.class)
                    .isThrownBy(() -> order.cancel(List.of("too late")))
                    .withMessageContaining("APPROVED");
        }
    }

    @Nested
    @DisplayName("failure messages")
    class FailureMessages {

        @Test
        @DisplayName("accumulate across saga steps rather than overwriting")
        void accumulate() {
            Order order = paidOrder();

            order.initCancel(List.of("payment declined"));
            order.cancel(List.of("payment reversed"));

            // A saga can fail at several steps; the last failure is not the whole story.
            assertThat(order.failureMessages())
                    .containsExactly("payment declined", "payment reversed");
        }

        @Test
        void nullAndBlankEntriesAreDiscarded() {
            Order order = pendingOrder();

            order.cancel(java.util.Arrays.asList("real reason", null, "   ", ""));

            assertThat(order.failureMessages()).containsExactly("real reason");
        }

        @Test
        void aNullListIsTolerated() {
            Order order = pendingOrder();

            order.cancel(null);

            assertThat(order.orderStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.failureMessages()).isEmpty();
        }
    }

    @Nested
    @DisplayName("reconstitute")
    class Reconstitute {

        @Test
        @DisplayName("trusts the stored total even when it disagrees with the lines")
        void storedTotalIsAuthoritative() {
            // The most valuable test in this class. A stored order was priced when it was placed;
            // if the menu price later changed, recomputing on load would silently rewrite what the
            // customer agreed to pay. This pins that reconstitute never re-derives.
            List<Order.PersistedItem> items = List.of(
                    new Order.PersistedItem(1, ROLEX_ID, "Rolex", 2, ugx("5000")));

            Order order = Order.reconstitute(
                    UUID.randomUUID(), CUSTOMER_ID, RESTAURANT_ID, UUID.randomUUID(),
                    address(), ugx("99999"), items, OrderStatus.PAID, List.of(), 3L);

            assertThat(order.price()).isEqualTo(ugx("99999"));
            assertThat(order.items()).singleElement()
                    .extracting(OrderItem::subTotal).isEqualTo(ugx("10000"));
        }

        @Test
        @DisplayName("restores a state create could never produce")
        void restoresTerminalStates() {
            Order order = Order.reconstitute(
                    UUID.randomUUID(), CUSTOMER_ID, RESTAURANT_ID, UUID.randomUUID(),
                    address(), ugx("22000"),
                    List.of(new Order.PersistedItem(1, ROLEX_ID, "Rolex", 2, ugx("8000"))),
                    OrderStatus.CANCELLED, List.of("payment failed"), 3L);

            assertThat(order.orderStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.failureMessages()).containsExactly("payment failed");
        }

        @Test
        @DisplayName("restores stored positions rather than renumbering from 1")
        void positionsAreRestoredExactly() {
            List<Order.PersistedItem> items = List.of(
                    new Order.PersistedItem(7, ROLEX_ID, "Rolex", 1, ugx("8000")),
                    new Order.PersistedItem(9, CHAPATI_ID, "Chapati", 1, ugx("2000")));

            Order order = Order.reconstitute(
                    UUID.randomUUID(), CUSTOMER_ID, RESTAURANT_ID, UUID.randomUUID(),
                    address(), ugx("10000"), items, OrderStatus.PENDING, List.of(), 3L);

            assertThat(order.items()).extracting(OrderItem::position).containsExactly(7, 9);
        }

        @Test
        @DisplayName("a created order is new until it has been persisted")
        void createdOrderIsNew() {
            Order order = pendingOrder();

            assertThat(order.version()).isEqualTo(Order.NEW_VERSION);
            assertThat(order.isNew()).isTrue();
        }

        @Test
        @DisplayName("a reconstituted order carries its stored version and is not new")
        void reconstitutedOrderCarriesItsVersion() {
            Order order = Order.reconstitute(
                    UUID.randomUUID(), CUSTOMER_ID, RESTAURANT_ID, UUID.randomUUID(),
                    address(), ugx("22000"),
                    List.of(new Order.PersistedItem(1, ROLEX_ID, "Rolex", 2, ugx("8000"))),
                    OrderStatus.PAID, List.of(), 7L);

            assertThat(order.version()).isEqualTo(7L);
            assertThat(order.isNew()).isFalse();
        }
    }

    @Nested
    @DisplayName("encapsulation")
    class Encapsulation {

        @Test
        void collectionsAreExposedUnmodifiable() {
            Order order = pendingOrder();

            assertThat(order.items()).isUnmodifiable();
            assertThat(order.failureMessages()).isUnmodifiable();
        }
    }
    }

