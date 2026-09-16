package com.allan.food.payment.adapter.out.persistence;

import com.allan.food.payment.application.port.out.PaymentPersistencePort;
import com.allan.food.payment.domain.model.CustomerCredit;
import com.allan.food.payment.domain.model.Payment;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Driven adapter fulfilling this context's persistence port with JPA.
 *
 * <p>One adapter, one port, two aggregates — grouped by technology, as {@code OrderPersistenceAdapter} is.
 * Not annotated {@code @Repository} and not {@code @Transactional}, for the reasons argued there: exception
 * translation has already happened behind the Spring Data proxies, and the transaction boundary belongs to the
 * use case, not the storage mechanism.
 *
 * <p><b>That last point is load-bearing in a saga.</b> A payment handler debits a balance and records a
 * payment; if the adapter opened its own transaction per call, those two writes could not be atomic and a crash
 * between them would leave a charge with no matching debit. The handler's transaction is what binds them, and
 * an adapter that started its own would silently break it.
 */
@Component
class PaymentPersistenceAdapter implements PaymentPersistencePort {

    private final PaymentJpaRepository paymentRepository;
    private final CustomerCreditJpaRepository creditRepository;

    PaymentPersistenceAdapter(PaymentJpaRepository paymentRepository,
                              CustomerCreditJpaRepository creditRepository) {
        this.paymentRepository = paymentRepository;
        this.creditRepository = creditRepository;
    }

    @Override
    public Optional<Payment> findPaymentByOrderId(UUID orderId) {
        return paymentRepository.findByOrderId(orderId).map(PaymentPersistenceMapper::toDomain);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code saveAndFlush}, so a unique-constraint violation on {@code order_id} or an optimistic-lock
     * conflict surfaces here rather than at commit, from the line that caused it. In a saga participant this
     * matters twice over: the exception is diagnosable, and it is thrown while the handler can still be
     * understood as the thing that failed.
     */
    @Override
    public Payment savePayment(Payment payment) {
        PaymentJpaEntity saved = paymentRepository.saveAndFlush(PaymentPersistenceMapper.toJpaEntity(payment));
        return PaymentPersistenceMapper.toDomain(saved);
    }

    @Override
    public Optional<CustomerCredit> findCredit(UUID customerId) {
        return creditRepository.findById(customerId).map(PaymentPersistenceMapper::toDomain);
    }

    @Override
    public CustomerCredit saveCredit(CustomerCredit credit) {
        CustomerCreditJpaEntity saved =
                creditRepository.saveAndFlush(PaymentPersistenceMapper.toJpaEntity(credit));
        return PaymentPersistenceMapper.toDomain(saved);
    }
}
