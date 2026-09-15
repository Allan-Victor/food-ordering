package com.allan.food.order.application.service;

import com.allan.food.order.application.exception.RestaurantNotFoundException;
import com.allan.food.order.application.port.in.CreateOrderUseCase;
import com.allan.food.order.application.port.in.command.CreateOrderCommand;
import com.allan.food.order.application.port.in.command.CreateOrderResult;
import com.allan.food.order.application.port.out.LoadRestaurantPort;
import com.allan.food.order.application.port.out.PublishEventPort;
import com.allan.food.order.application.port.out.SaveOrderPort;
import com.allan.food.order.domain.OrderDomainService;
import com.allan.food.order.domain.model.entity.Order;
import com.allan.food.order.domain.model.entity.Restaurant;
import com.allan.food.order.domain.model.valueobject.StreetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Orchestrates the "place an order" use case.
 *
 * <p><b>This class contains no business rules, and that is the point.</b> Its whole job is the four-step
 * choreography: resolve the restaurant replica, hand the request to the domain, persist the resulting
 * aggregate, record the event. Every rule — is the restaurant accepting orders, does the product exist, what
 * does a line cost, what is the total — lives in {@code Restaurant}, {@code Order}, and
 * {@code OrderDomainService}. If a business rule ever appears in this file, the model has sprung a leak.
 *
 * <p><b>One service per use case (buckpal's convention), not one fat application service.</b> The reference
 * exposes a single {@code OrderApplicationService} covering create and track. Create needs four collaborators;
 * track needs one — bundling them produces a class where each method uses a fraction of its dependencies, and
 * the two paths want different transaction semantics. {@link TrackOrderService} is the read-side sibling.
 *
 * <p><b>No {@code OrderCreateHelper} indirection.</b> The reference splits the transactional work into a second
 * bean so that Spring's proxy applies and the event publish lands <i>after</i> commit (a self-invocated
 * {@code @Transactional} method is not intercepted). We don't need the workaround because we publish
 * <i>inside</i> the transaction and let {@link PublishEventPort}'s adapter own delivery timing — see that
 * port's documentation for the full argument.
 *
 * <p><b>Not {@code final}, unlike our domain classes:</b> Spring proxies this for {@code @Transactional} and
 * {@code @Validated} using CGLIB, which subclasses the target. Package-private visibility is deliberate — the
 * web adapter depends on {@link CreateOrderUseCase}, never on this implementation, and the compiler enforces
 * that. CGLIB generates the proxy into this same package, so package-private is not an obstacle.
 */
@Service
@Validated
class CreateOrderService implements CreateOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateOrderService.class);

    private final OrderDomainService orderDomainService;
    private final LoadRestaurantPort loadRestaurantPort;
    private final SaveOrderPort saveOrderPort;
    private final PublishEventPort publishEventPort;

    CreateOrderService(OrderDomainService orderDomainService,
                       LoadRestaurantPort loadRestaurantPort,
                       SaveOrderPort saveOrderPort,
                       PublishEventPort publishEventPort) {
        this.orderDomainService = orderDomainService;
        this.loadRestaurantPort = loadRestaurantPort;
        this.saveOrderPort = saveOrderPort;
        this.publishEventPort = publishEventPort;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Note the absence of {@code @Valid} here.</b> It is declared once, on {@link CreateOrderUseCase}.
     * Jakarta Bean Validation forbids redeclaring parameter constraints down an override hierarchy — doing so
     * throws {@code ConstraintDeclarationException} at startup. The {@code @Validated} on this class is what
     * activates interception of the interface's constraints.
     *
     * <p><b>No try/catch.</b> {@code OrderDomainException} and the not-found exceptions are unchecked, so
     * {@code @Transactional} rolls back automatically and the boundary advice translates them to HTTP. Catching
     * and rethrowing here would only obscure stack traces.
     */
    @Override
    @Transactional
    public CreateOrderResult createOrder(CreateOrderCommand command) {
        /*
        * 1. Resolve the read-only replica. Absence is an application-level failure: the domain is never
        *    handed a half -resolved Restaurant (contrast the reference's query-by-example repository).
        */
        Restaurant restaurant = loadRestaurantPort.load(command.restaurantId())
                .orElseThrow(() -> new RestaurantNotFoundException(command.restaurantId()));

        /*
         * 2. Hand off to the domain. It confirms every line against the replica, derives all money, and
         *    returns both the aggregate and the event describing what happened. We supply no prices.
         */
        OrderDomainService.OrderCreationResult creation = orderDomainService.createOrder(
                command.customerId(),
                toStreetAddress(command.address()),
                toRequestedItems(command.items()),
                restaurant);

        /*
         * 3. Persist, then use the returned aggregate rather than the argument. Identical today (our ids are
         *    domain-generated), but it is the correct habit for when the adapter enriches the aggregate -
         *    the optimistic-locking @Version arriving in Slice 2.
         */
        Order saved = saveOrderPort.save(creation.order());

        /*
         * 4. Record the event inside the same transaction. Delivery timing belongs to the adapter.
         */
        publishEventPort.publish(creation.event());

        log.info("Order {} created for customer {} with tracking id {}",
                saved.orderId(), saved.customerId(), saved.trackingId());

        return new CreateOrderResult(saved.trackingId());
    }

    /**
     * Translates the boundary's address shape into the domain value object.
     *
     * <p><b>Decision:</b> private static methods rather than an injected {@code OrderDataMapper} component.
     * The reference registers a Spring bean for mapping; ours is six lines of pure function with no state and
     * no dependencies, so a bean would be ceremony — and a {@code static} method advertises the purity. The
     * mapping that genuinely earns a dedicated class is the domain↔JPA one in the persistence adapter, where
     * both sides are non-trivial.
     *
     * <p>Note what is <i>absent</i>: the reference mints a {@code UUID.randomUUID()} identity for the address
     * here. A typed-in delivery address has no identity of its own; ours is a pure value object.
     */
    private static StreetAddress toStreetAddress(CreateOrderCommand.OrderAddressDto address) {
        return new StreetAddress(address.street(), address.postalCode(), address.city());
    }

    /**
     * Translates the boundary's line shape into the domain service's request shape.
     *
     * <p>A near-identity mapping — product plus quantity, in and out — which is precisely the evidence that
     * removing money from the inbound command was right. The reference's equivalent method is three times this
     * size because it has to carry client-supplied prices and sub-totals across the boundary and wrap them in
     * {@code Money} objects the server is then obliged to trust.
     */
    private static List<OrderDomainService.RequestedItem> toRequestedItems(List<CreateOrderCommand.OrderItemDto> items) {
        return items.stream()
                .map(item -> new OrderDomainService.RequestedItem(item.productId(), item.quantity()))
                .toList();
    }
}
