package com.allan.food.payment.application.port.out;

import com.allan.food.payment.domain.model.CustomerCredit;
import com.allan.food.payment.domain.model.Payment;

import java.util.Optional;
import java.util.UUID;

/**
 * Driven port for this context's persistence.
 *
 * <p><b>One port covering two aggregates, unlike the order context's split.</b> There we separated
 * {@code SaveOrderPort} from {@code LoadOrderPort} because different use-case services needed different halves.
 * Here a single service performs every operation, so the split would produce four interfaces with one
 * implementation and one caller — segregation with nobody to serve. The rule is "split by reason to depend,"
 * and there is only one depender.
 *
 * <p>{@link #findPaymentByOrderId} is the idempotency lookup and the reason this port exists in the shape it
 * does: the participant's ability to recognise repeated work is a persistence question before it is a logic
 * question.
 */
public interface PaymentPersistencePort {

    /** The idempotency lookup: has this order already been charged, successfully or not? */
    Optional<Payment> findPaymentByOrderId(UUID orderId);

    Payment savePayment(Payment payment);

    Optional<CustomerCredit> findCredit(UUID customerId);

    CustomerCredit saveCredit(CustomerCredit credit);
}
