package com.allan.food.order.adapter.out.persistence;

import com.allan.food.order.domain.model.entity.Order;
import com.allan.food.order.domain.model.entity.OrderItem;
import com.allan.food.order.domain.model.valueobject.Money;
import com.allan.food.order.domain.model.valueobject.StreetAddress;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Translates between the domain's {@code Order} aggregate and its {@link OrderJpaEntity} row shape.
 *
 * <p><b>This class is the price of a framework-free domain, and it is the whole price.</b> Because it exists,
 * {@code Order} has no {@code jakarta.persistence} import, no no-arg constructor, no non-final fields and no
 * mutable collections — it can be constructed and exercised with JPA absent from the classpath entirely. Every
 * concession the specification demands is absorbed here instead. The pragmatic alternative deletes this file
 * and the three embeddables by annotating the domain directly; that is what most shops ship, and step 9 builds
 * it side by side so the comparison is concrete.
 *
 * <p><b>A utility class of static methods, not an injected bean.</b> These are pure functions of their
 * arguments — no state, no collaborators, no I/O — so instance identity would be meaningless and injection
 * would only obscure that. Effective Java, Item 4: a private constructor makes the class non-instantiable and
 * says so at compile time. The reference registers its {@code OrderDataMapper} as a {@code @Component},
 * which invites the mistake of later giving a stateless translator a dependency.
 *
 * <p><b>Why hand-written rather than MapStruct.</b> MapStruct excels at getter/setter beans; this mapping is
 * its worst case — a package-visible-by-convention static factory consuming a transformed and sorted list,
 * records with validating canonical constructors, no setters anywhere on the target. Encoding that in
 * {@code @ObjectFactory} and {@code expression =} attributes is more configuration than the code below, and
 * debugging it means reading generated sources.
 *
 * <p><b>Note the asymmetry between the two directions, because it is the model speaking.</b>
 * {@link #toJpaEntity} is a flattening: an aggregate that enforces invariants becomes an inert row.
 * {@link #toDomain} is a <i>reconstitution</i>: it must not re-derive anything. It routes through
 * {@code Order.reconstitute}, which trusts stored state, rather than {@code Order.create}, which enforces
 * creation rules. Reading a two-year-old order must never re-run today's pricing against yesterday's facts.
 * Getting this backwards is one of the most damaging bugs available in a persistence layer, because it
 * corrupts silently and only under conditions — a changed menu price — that no unit test simulates.
 */
final class OrderPersistenceMapper {

    /** Non-instantiable: this class is a namespace for functions. */
    private OrderPersistenceMapper() {
        throw new AssertionError("No instances.");
    }

    /**
     * Flattens an aggregate into a detached row graph ready for {@code save}.
     *
     * <p><b>A fresh entity every time, deliberately — and this is the real bill for strict purity.</b> Because
     * the domain object is not a managed entity, we cannot mutate a loaded row and let Hibernate's dirty
     * checking emit a minimal {@code UPDATE}; we hand the provider a detached graph and it works out the
     * difference. From Slice 2 the item collection is therefore rewritten wholesale on every update. That is a
     * genuine, measurable cost, worth carrying knowingly.
     *
     * <p><b>Version translation is where the two newness conventions meet.</b> The domain uses
     * {@link Order#NEW_VERSION} for "never persisted"; Spring Data uses a null non-primitive version for the
     * same idea. Mapping the sentinel to {@code null} is what lets a new aggregate be inserted without a
     * preceding {@code SELECT}, and mapping a real version through is what arms the optimistic lock on an
     * update. Both halves are needed, and getting either wrong fails silently rather than loudly — an
     * always-null version means the lock never engages, an always-non-null one means every insert is preceded
     * by a pointless select and a merge.
     */
    static OrderJpaEntity toJpaEntity(Order order) {
        return OrderJpaEntity.builder()
                .id(order.orderId())
                .version(order.isNew() ? null : order.version())
                .customerId(order.customerId())
                .restaurantId(order.restaurantId())
                .trackingId(order.trackingId())
                .price(toMoneyEmbeddable(order.price()))
                .deliveryAddress(toAddressEmbeddable(order.deliveryAddress()))
                .orderStatus(order.orderStatus())
                .items(new ArrayList<>(order.items().stream()
                        .map(OrderPersistenceMapper::toItemEmbeddable)
                        .toList()))
                .failureMessages(new ArrayList<>(order.failureMessages()))
                .build();
    }

    /**
     * Reconstitutes an aggregate from stored state.
     *
     * <p><b>Sorting by position is load-bearing, not tidiness.</b> {@code @ElementCollection} gives no ordering
     * guarantee — the provider returns rows in whatever order the database yields, which varies with plan and
     * even between executions of the same query. Position is domain-meaningful (1, 2, 3 scoped to the order,
     * shown to the customer), so it must be re-established explicitly on read. We sort here rather than with
     * {@code @OrderColumn} because ordering is data the domain owns, not bookkeeping the provider maintains.
     *
     * <p>Note this method must be called with the transaction still open: both collections are {@code LAZY},
     * and touching them here is what triggers their loading. Our application services are
     * {@code @Transactional}, and the adapter runs inside that boundary, so the session is live.
     */
    static Order toDomain(OrderJpaEntity entity) {
        List<Order.PersistedItem> items = entity.getItems().stream()
                .sorted(Comparator.comparingInt(OrderItemEmbeddable::getPosition))
                .map(OrderPersistenceMapper::toPersistedItem)
                .toList();

        return Order.reconstitute(
                entity.getId(),
                entity.getCustomerId(),
                entity.getRestaurantId(),
                entity.getTrackingId(),
                toAddress(entity.getDeliveryAddress()),
                toMoney(entity.getPrice()),
                items,
                entity.getOrderStatus(),
                List.copyOf(entity.getFailureMessages()),
                entity.getVersion() == null ? Order.NEW_VERSION : entity.getVersion());
    }

    // ---------------------------------------------------------------------
    // Value-object translation
    // ---------------------------------------------------------------------

    private static MoneyEmbeddable toMoneyEmbeddable(Money money) {
        return new MoneyEmbeddable(money.amount(), money.currency());
    }

    /**
     * <p>Routes through {@code Money}'s canonical constructor, so the stored amount is re-scaled to the
     * currency's own fraction digits on the way in. A UGX amount persisted as {@code 8000.0000} in the
     * fixed-scale column returns as {@code 8000}, and a JOD amount keeps its three decimals — behaviour the
     * reference's hardcoded scale of 2 cannot produce.
     */
    private static Money toMoney(MoneyEmbeddable embeddable) {
        return new Money(embeddable.getAmount(), embeddable.getCurrency());
    }

    private static StreetAddressEmbeddable toAddressEmbeddable(StreetAddress address) {
        return new StreetAddressEmbeddable(address.street(), address.postalCode(), address.city());
    }

    private static StreetAddress toAddress(StreetAddressEmbeddable embeddable) {
        return new StreetAddress(embeddable.getStreet(), embeddable.getPostalCode(), embeddable.getCity());
    }

    private static OrderItemEmbeddable toItemEmbeddable(OrderItem item) {
        return new OrderItemEmbeddable(
                item.position(),
                item.productId(),
                item.productName(),
                item.quantity(),
                toMoneyEmbeddable(item.price()));
    }

    /**
     * <p>{@code subTotal} is absent by design: {@code PersistedItem} does not carry one, because the domain
     * re-derives it from the stored price and quantity. Both inputs are themselves trusted stored state, so the
     * derived value is identical to the original — nothing is recomputed against today's menu.
     */
    private static Order.PersistedItem toPersistedItem(OrderItemEmbeddable embeddable) {
        return new Order.PersistedItem(
                embeddable.getPosition(),
                embeddable.getProductId(),
                embeddable.getProductName(),
                embeddable.getQuantity(),
                toMoney(embeddable.getPrice()));
    }
}
