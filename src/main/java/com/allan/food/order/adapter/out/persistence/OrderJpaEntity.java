package com.allan.food.order.adapter.out.persistence;

import com.allan.food.order.domain.model.valueobject.OrderStatus;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;
import java.util.UUID;

/**
 * The order aggregate's row shape. A persistence detail with no business meaning — every rule lives in the
 * domain's {@code Order}, and this class exists only so that one can be written to and read from a database
 * without knowing a database exists.
 *
 * <p><b>Why it imports a domain type ({@link OrderStatus}) and that is fine.</b> The dependency rule forbids
 * <i>domain → adapter</i>, not <i>adapter → domain</i>; adapters are supposed to know the core. Duplicating the
 * enum here would add a mapping step that buys nothing. The cost to accept: with
 * {@code EnumType.STRING}, renaming a domain constant becomes a data migration. That is the right trade — the
 * alternative, {@code EnumType.ORDINAL}, makes <i>reordering</i> the constants silently corrupt every stored
 * row, which is a far worse failure because it produces no error at all.
 *
 * <p><b>On {@code @Version} and Spring Data's newness detection.</b> Our identifiers are domain-generated, so
 * this entity always reaches the repository with a non-null {@code @Id}. Spring Data's {@code save()} asks
 * {@code isNew()}; with no version attribute it reads a non-null id as "already exists" and issues a
 * {@code merge()} — a {@code SELECT} before every single {@code INSERT}, plus detached-copy semantics nobody
 * asked for. When a <i>non-primitive</i> version attribute is present, newness becomes {@code version == null}
 * instead, and inserts go straight through.
 *
 * <p><b>Hence {@code Long}, not {@code long}.</b> {@code JpaMetamodelEntityInformation} explicitly skips a
 * primitive version and falls back to id-based detection — a primitive defaults to {@code 0} and can never be
 * null. The wrapper is not stylistic; it is the mechanism.
 *
 * <p>The field earns its place twice over: it is also the optimistic lock the saga needs from Slice 2, when
 * payment and restaurant-approval responses can race to mutate the same order. Vernon treats a version on the
 * aggregate root as standard aggregate design rather than an optimisation.
 *
 * <p><b>Both collections are {@code LAZY}, deliberately.</b> Marking two {@code List} element collections
 * {@code EAGER} throws {@code MultipleBagFetchException} at startup — Hibernate cannot join-fetch two
 * unordered bags without producing a cartesian product. Lazy is safe here because the mapper runs inside the
 * transaction opened by the application service, so the session is still live; the cost is two extra selects
 * per aggregate load, which is the correct price for loading a whole aggregate.
 *
 * <p><b>Table named {@code orders}:</b> {@code ORDER} is a SQL reserved word.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
class OrderJpaEntity {
    /**
     * Assigned by the domain, never by the database.
     *
     * <p>This sidesteps the classic JPA identity trap: with a generated id, an entity's {@code hashCode}
     * changes the moment it is persisted, breaking any {@code HashSet} it was already in — which is why so much
     * JPA advice prescribes a constant {@code hashCode}. A domain-assigned id is stable from construction, so
     * plain id-based equality is simply correct.
     */
    @Id
    @EqualsAndHashCode.Include
    private UUID id;

    /** Wrapper type, not primitive — see the class documentation. Managed entirely by the provider. */
    @Version
    private Long version;

    @Column(nullable = false)
    private UUID customerId;

    @Column(nullable = false)
    private UUID restaurantId;

    /** Unique: it is the customer-facing lookup key for {@code LoadOrderPort.loadByTrackingId}. */
    @Column(nullable = false, unique = true)
    private UUID trackingId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "price_amount", nullable = false, precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "price_currency", nullable = false, length = 3))
    })
    private MoneyEmbeddable price;

    @Embedded
    private StreetAddressEmbeddable deliveryAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus orderStatus;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
    private List<OrderItemEmbeddable> items;

    /**
     * <b>A collection of rows, not a delimited string.</b> The reference joins failure messages into one column
     * with a separator — which corrupts silently the first time a message contains that separator, and it will,
     * because these strings originate from other services. One row per message also preserves accumulation
     * order, which matters since the domain appends rather than overwrites.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "order_failure_messages", joinColumns = @JoinColumn(name = "order_id"))
    @Column(name = "message", length = 500)
    private List<String> failureMessages;
}
