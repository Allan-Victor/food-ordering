package com.allan.food.order.adapter.in.web;

import com.allan.food.order.application.port.in.CreateOrderUseCase;
import com.allan.food.order.application.port.in.TrackOrderUseCase;
import com.allan.food.order.application.port.in.command.CreateOrderCommand;
import com.allan.food.order.application.port.in.command.CreateOrderResult;
import com.allan.food.order.application.port.in.command.TrackOrderQuery;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Driving adapter translating HTTP into use-case invocations.
 *
 * <p><b>Everything this class does is translation.</b> Deserialize, validate the wire shape, build a command,
 * call a port, shape the result, pick a status code. No conditionals on business state, no orchestration, no
 * knowledge that a restaurant replica or a domain event exists. Its dependencies are two interfaces; it cannot
 * reach a repository or an aggregate even if someone tried, because the compiler will not let it.
 *
 * <p><b>Depends on both use-case ports, and that is not a contradiction of the segregation.</b> Ports are split
 * so that each <i>implementation</i> declares only what it needs; a controller genuinely exercising both
 * operations depending on both is the segregation working, not failing. What matters is that it depends on
 * {@link CreateOrderUseCase} and {@link TrackOrderUseCase} rather than on {@code CreateOrderService} and
 * {@code TrackOrderService} — those are package-private and unreachable from here.
 *
 * <p>Package-private, like every adapter in this codebase. Spring MVC registers it by component scan; nothing
 * outside this package has any reason to name it.
 *
 * <p><b>Path versioned from day one</b> ({@code /api/v1}). Adding a version to a live API is a migration;
 * having one from the start costs six characters.
 */
@RestController
@RequestMapping("/api/v1/orders")
class OrderController {
    private final CreateOrderUseCase createOrderUseCase;
    private final TrackOrderUseCase trackOrderUseCase;

    OrderController(CreateOrderUseCase createOrderUseCase, TrackOrderUseCase trackOrderUseCase) {
        this.createOrderUseCase = createOrderUseCase;
        this.trackOrderUseCase = trackOrderUseCase;
    }

    /**
     * Places an order.
     *
     * <p><b>201 with a {@code Location} header, not 200 with a body to parse.</b> The request created a
     * resource, so HTTP has a status that says exactly that, and a header that says where it now lives. A client
     * follows the header rather than reassembling a URL from an id it found in the body. The reference returns
     * 200 with the tracking id buried in a payload — workable, but it discards protocol-level meaning that costs
     * nothing to keep.
     *
     * <p><b>{@code UriComponentsBuilder} as a method parameter</b> rather than
     * {@code ServletUriComponentsBuilder.fromCurrentRequest()}. Spring resolves it per request and it is honest
     * about the dependency; the static variant reads the current request from a thread-local, which is invisible
     * at the signature and awkward to exercise in a slice test.
     *
     * <p>Failures need no handling here. {@code RestaurantNotFoundException} and {@code OrderDomainException}
     * propagate to {@link OrderExceptionHandler}, which owns the exception-to-status mapping in one place.
     */
    @PostMapping
    ResponseEntity<OrderResponses.OrderCreated> createOrder(@Valid @RequestBody CreateOrderRequest request,
                                                            UriComponentsBuilder uriBuilder) {
        CreateOrderResult result = createOrderUseCase.createOrder(toCommand(request));

        URI location = uriBuilder.path("/api/v1/orders/{trackingId}")
                .buildAndExpand(result.trackingId())
                .toUri();
        return ResponseEntity.created(location)
                .body(new OrderResponses.OrderCreated(result.trackingId()));
    }

    /**
     * Reports an order's current state.
     *
     * <p>Returns the body directly rather than a {@code ResponseEntity}: the status is always 200 and there are
     * no headers to set, so wrapping would add a layer that says nothing. Absence surfaces as
     * {@code OrderNotFoundException} and becomes a 404 at the handler.
     *
     * <p>Spring converts the path segment to {@code UUID} during binding; a malformed one produces a 400 before
     * this method is entered.
     */
    @GetMapping("/{trackingId}")
    OrderResponses.OrderTracking trackOrder(@PathVariable UUID trackingId) {
        return OrderResponses.OrderTracking.from(
                trackOrderUseCase.trackOrder(new TrackOrderQuery(trackingId)));
    }

    /**
     * Builds the command from the request.
     *
     * <p><b>This is the method that will change when authentication arrives</b>, taking {@code customerId} from
     * the principal instead of the body — and nothing else in the system will notice. Worth pausing on: it is
     * the concrete payoff for a wire model that looked like pure duplication three files ago.
     *
     * <p>Static and private, consistent with the mapping helpers in {@code CreateOrderService}. An alternative
     * you will see is a {@code toCommand()} method on the request record itself; equally defensible, and it
     * keeps the controller shorter. We put it here so that all knowledge of the application layer sits in the
     * class that talks to it, leaving the request record a pure data shape.
     */
    private static CreateOrderCommand toCommand(CreateOrderRequest request) {
        return new CreateOrderCommand(
                request.customerId(),
                request.restaurantId(),
                toCommandItems(request.items()),
                new CreateOrderCommand.OrderAddressDto(
                        request.address().street(),
                        request.address().postalCode(),
                        request.address().city()));
    }

    private static List<CreateOrderCommand.OrderItemDto> toCommandItems(List<CreateOrderRequest.Item> items) {
        return items.stream()
                .map(item -> new CreateOrderCommand.OrderItemDto(item.productId(), item.quantity()))
                .toList();
    }
}
