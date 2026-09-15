package com.allan.food.order.domain.model.entity;

import com.allan.food.order.domain.model.valueobject.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.allan.food.order.OrderTestData.ROLEX_ID;
import static com.allan.food.order.OrderTestData.ugx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("OrderItem")
class OrderItemTest {

    @Test
    @DisplayName("derives its subtotal rather than accepting one")
    void derivesSubTotal() {
        OrderItem item = OrderItem.of(1, ROLEX_ID, "Rolex", 3, ugx("8000"));

        assertThat(item.subTotal()).isEqualTo(ugx("24000"));
        assertThat(item.price()).isEqualTo(ugx("8000"));
    }

    @Nested
    @DisplayName("rejects invalid input at construction")
    class Guards {

        @Test
        void nonPositivePosition() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> OrderItem.of(0, ROLEX_ID, "Rolex", 1, ugx("8000")))
                    .withMessageContaining("position");
        }

        @Test
        @DisplayName("non-positive quantity -which is why Order's zero-total guard is unreachable")
        void nonPositiveQuantity() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> OrderItem.of(1, ROLEX_ID, "Rolex", 0, ugx("8000")))
                    .withMessageContaining("quantity");
        }

        @Test
        void zeroPrice() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> OrderItem.of(1, ROLEX_ID, "Rolex", 1, Money.zero("UGX")));
        }

        @Test
        void nullProductId() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> OrderItem.of(1, null, "Rolex", 1, ugx("8000")));
        }

        @Test
        void blankProductName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> OrderItem.of(1, ROLEX_ID, "  ", 1, ugx("8000")));
        }

        @Test
        void nullPrice() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> OrderItem.of(1, ROLEX_ID, "Rolex", 1, null));
        }
    }

    @Nested
    @DisplayName("identity is the position within its order")
    class Identity {
        @Test
        @DisplayName("same position, different product but still the same line")
        void equalByPositionAlone() {
            OrderItem first = OrderItem.of(1, ROLEX_ID, "Rolex", 2, ugx("8000"));
            OrderItem second = OrderItem.of(1, UUID.randomUUID(), "Chapati", 9, ugx("2000"));

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("same product, different position - two distinct lines")
        void differentPositionsAreDistinct() {
            OrderItem first = OrderItem.of(1, ROLEX_ID, "Rolex", 2, ugx("8000"));
            OrderItem second = OrderItem.of(2, ROLEX_ID, "Rolex", 2, ugx("8000"));

            // The reason this entity is not a value object: a customer may add the same product
            // twice on purpose, and a Set must not collapse the two lines into one.
            assertThat(first).isNotEqualTo(second);

        }
    }
}
