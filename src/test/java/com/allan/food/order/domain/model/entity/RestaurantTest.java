package com.allan.food.order.domain.model.entity;

import com.allan.food.order.domain.exception.OrderDomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.allan.food.order.OrderTestData.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("Restaurant")
class RestaurantTest {

    @Test
    void resolvesAProductionOnItsMenu() {
        assertThat(activeRestaurant().requireProduct(ROLEX_ID))
                .isEqualTo(rolex());
    }

    @Test
    @DisplayName("throws on a product it does not sell, rather than returning empty")
    void unknownProductIsABusinessError() {
        assertThatExceptionOfType(OrderDomainException.class)
                .isThrownBy(() -> activeRestaurant().requireProduct(UNKNOWN_PRODUCT_ID))
                .withMessageContaining(UNKNOWN_PRODUCT_ID.toString());
    }

    @Test
    @DisplayName("an inactive restaurant still resolves products - activity is the domain service's check")
    void inactiveStillResolves() {
        assertThat(inactiveRestaurant().active()).isFalse();
        assertThat(inactiveRestaurant().requireProduct(ROLEX_ID)).isEqualTo(rolex());
    }

    @Test
    void exposesAnUnmodifiableMenu() {
        assertThat(activeRestaurant().products()).containsExactlyInAnyOrder(rolex(), chapati());
    }

    @Test
    @DisplayName("duplicate product ids fail at construction, from the map collector")
    void duplicateProductsRejected() {
        List<Product> duplicated = List.of(rolex(), rolex());

        // IllegalStateException, not OrderDomainException — it comes from toUnmodifiableMap's
        // merge check, not from one of our guards. Worth pinning so the type is not a surprise.
        assertThatIllegalStateException()
                .isThrownBy(() -> Restaurant.of(RESTAURANT_ID, true, duplicated));
    }

    @Test
    void requiresIdAndAtLeastOneProduct() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Restaurant.of(null, true, List.of(rolex())));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Restaurant.of(UUID.randomUUID(), true, List.of()));
    }
}
