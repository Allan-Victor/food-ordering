package com.allan.food.order.application.service;

import com.allan.food.order.application.exception.OrderNotFoundException;
import com.allan.food.order.application.port.in.TrackOrderUseCase;
import com.allan.food.order.application.port.in.command.TrackOrderQuery;
import com.allan.food.order.application.port.in.command.TrackOrderResponse;
import com.allan.food.order.application.port.out.LoadOrderPort;
import com.allan.food.order.domain.model.entity.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/**
 * Serves the "where is my order?" use case — the read side, deliberately a separate class from
 * {@link CreateOrderService}.
 *
 * <p><b>Why its own class:</b> it needs exactly one collaborator. Folded into a combined application service it
 * would sit beside three dependencies it never touches. The dependency list of a well-scoped use-case service
 * <i>is</i> its documentation; keeping it honest is the point.
 *
 * <p><b>Why {@code readOnly = true}:</b> more than a hint. It puts Hibernate's flush mode to {@code MANUAL},
 * so the persistence context skips dirty-checking on exit — a real saving on a hot polling endpoint — and it
 * makes the intent explicit: nothing in this path may mutate state. It is also the hook that lets
 * infrastructure route reads to a replica later without touching this code.
 *
 * <p><b>A note on what this deliberately is not:</b> a mature system would eventually serve tracking from a
 * denormalised read model rather than rehydrating the full aggregate — the query side of CQRS proper. At this
 * scale that would be premature; loading the aggregate is honest and correct. The port boundary means the
 * upgrade, if it ever comes, is contained.
 */
@Service
@Validated
class TrackOrderService implements TrackOrderUseCase {

    private final LoadOrderPort loadOrderPort;

    TrackOrderService(LoadOrderPort loadOrderPort) {
        this.loadOrderPort = loadOrderPort;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Constraints are declared once on {@link TrackOrderUseCase}; {@code @Validated} above activates them.
     */
    @Override
    @Transactional(readOnly = true)
    public TrackOrderResponse trackOrder(TrackOrderQuery query) {
        Order order = loadOrderPort.loadByTrackingId(query.trackingId())
                .orElseThrow(() -> new OrderNotFoundException(query.trackingId()));

        return new TrackOrderResponse(
                order.trackingId(),
                order.orderStatus(),
                order.failureMessages());
    }
}
