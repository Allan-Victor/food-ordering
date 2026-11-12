package com.allan.food.temporal.config;

import com.allan.food.temporal.OrderSagaWorkflowImpl;
import com.allan.food.temporal.OrderStateActivities;
import com.allan.food.temporal.PaymentActivities;
import com.allan.food.temporal.RestaurantActivities;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Temporal client and worker for this branch — the one piece with no equivalent on {@code main},
 * since the message-based saga had no separate process to register.
 *
 * <p>One task queue, one worker, all four saga types on it — proportionate to a single small server, the same
 * reasoning that ruled out a real Temporal cluster for production. Splitting by bounded context is the natural
 * next step only if this ever needed independent scaling per participant, which it doesn't at this scale.
 */
@Configuration
public class TemporalConfig {

    public static final String TASK_QUEUE = "order-saga-task-queue";

    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs workflowServiceStubs() {
        // Matches temporal server start-dev's default endpoint. No production connection options
        // belong here. See the branch's infra-cost discussion for why that's a separate decision.
        return WorkflowServiceStubs.newLocalServiceStubs();
    }

    @Bean
    WorkflowClient workflowClient(WorkflowServiceStubs stubs) {
        return WorkflowClient.newInstance(stubs);
    }

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    WorkerFactory workerFactory(WorkflowClient client,
                                OrderStateActivities orderStateActivities,
                                PaymentActivities paymentActivities,
                                RestaurantActivities restaurantActivities) {
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(OrderSagaWorkflowImpl.class);
        worker.registerActivitiesImplementations(orderStateActivities, paymentActivities, restaurantActivities);
        return factory;
    }
}
