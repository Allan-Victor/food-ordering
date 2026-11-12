package com.allan.food.order.adapter.in.saga;

import com.allan.food.order.application.port.out.LoadOrderPort;
import com.allan.food.order.domain.event.OrderCreatedEvent;
import com.allan.food.order.domain.model.entity.Order;
import com.allan.food.saga.contract.SagaContract.ApproveOrder;
import com.allan.food.temporal.OrderSagaWorkflow;
import com.allan.food.temporal.OrderSagaWorkflow.Input;
import com.allan.food.temporal.config.TemporalConfig;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.NoSuchElementException;
/**
 * Replaces {@code OrderSaga.onOrderCreated} as what fires when an order is created — the one class on this
 * branch that still listens for a Spring application event, since {@code CreateOrderService} itself is
 * unchanged and still publishes {@link OrderCreatedEvent} through {@code PublishEventPort} exactly as on
 * {@code main}. Only what's downstream of that publish changes.
 *
 * <h2>{@code AFTER_COMMIT}, still, even though nothing here writes to the database</h2>
 *
 * The safety property this preserves isn't about this class's own I/O — it's that a workflow must never start
 * for an order that rolled back. That's exactly what {@code AFTER_COMMIT} guaranteed for
 * {@code OrderSaga.onOrderCreated}, unchanged here.
 *
 * <h2>No {@code @Transactional(REQUIRES_NEW)}, unlike every other listener this package has had</h2>
 *
 * That rule exists for {@code AFTER_COMMIT} listeners that write to the database and find no transaction bound
 * to the thread. {@link WorkflowClient#start} is a gRPC call to the Temporal service, not a database write —
 * there's nothing here for a transaction to protect.
 *
 * <h2>Why this loads the order rather than changing {@code OrderCreatedEvent}</h2>
 *
 * Adding line items to the event would mean bending the domain model to satisfy this branch's orchestration
 * layer — backwards from every other decision made on this branch. The order was committed moments ago by the
 * same transaction that raised this event, so loading it here is the honest cost of gathering what Temporal
 * needs at start time, not a domain change.
 */
class OrderSagaWorkflowStarter {

    private final WorkflowClient workflowClient;
    private final LoadOrderPort loadOrderPort;

    OrderSagaWorkflowStarter(WorkflowClient workflowClient, LoadOrderPort loadOrderPort) {
        this.workflowClient = workflowClient;
        this.loadOrderPort = loadOrderPort;
    }

    @TransactionalEventListener
    public void on(OrderCreatedEvent event) {
        Order order = loadOrderPort.loadById(event.orderId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Order %s not found immediately after its own creation event".formatted(event.orderId())));

        List<ApproveOrder.Line> lines = order.items().stream()
                .map(item -> new ApproveOrder.Line(item.productId(), item.quantity()))
                .toList();

        Input input = new Input(event.orderId(), event.customerId(),
                event.price().amount(), event.price().currency(), event.restaurantId(), lines);

        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalConfig.TASK_QUEUE)
                .setWorkflowId(event.orderId().toString())
                .build();

        OrderSagaWorkflow workflow = workflowClient.newWorkflowStub(OrderSagaWorkflow.class, options);
        WorkflowClient.start(workflow::run, input);
    }
}
