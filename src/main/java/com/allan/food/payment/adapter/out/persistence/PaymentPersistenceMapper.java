package com.allan.food.payment.adapter.out.persistence;

import com.allan.food.payment.domain.model.CustomerCredit;
import com.allan.food.payment.domain.model.Money;
import com.allan.food.payment.domain.model.Payment;

/**
 * Translates between this context's aggregates and their row shapes.
 *
 * <p>The same hand-written, static, non-instantiable translator as the order context's, for the same reasons —
 * see {@code OrderPersistenceMapper} for the argument against MapStruct and against registering it as a bean.
 * Shorter here because these aggregates have no collections and no derived state.
 *
 * <p><b>Both directions translate the newness sentinel.</b> The domain says {@code NEW_VERSION} (-1); Spring
 * Data says null. Mapping between them is what lets an insert skip its pre-select and an update arm its lock.
 * Getting either half wrong fails silently rather than loudly, which is why it is stated explicitly at both
 * call sites rather than hidden in a helper.
 */
final class PaymentPersistenceMapper {

    private PaymentPersistenceMapper() {
        throw new AssertionError("No instances.");
    }

    static PaymentJpaEntity toJpaEntity(Payment payment) {
        return PaymentJpaEntity.builder()
                .id(payment.paymentId())
                .version(payment.isNew() ? null : payment.version())
                .orderId(payment.orderId())
                .customerId(payment.customerId())
                .amount(toMoneyEmbeddable(payment.amount()))
                .status(payment.status())
                .createdAt(payment.createdAt())
                .build();
    }

    static Payment toDomain(PaymentJpaEntity entity) {
        return Payment.reconstitute(
                entity.getId(),
                entity.getOrderId(),
                entity.getCustomerId(),
                toMoney(entity.getAmount()),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getVersion() == null ? Payment.NEW_VERSION : entity.getVersion());
    }

    static CustomerCreditJpaEntity toJpaEntity(CustomerCredit credit) {
        return CustomerCreditJpaEntity.builder()
                .customerId(credit.customerId())
                .version(credit.isNew() ? null : credit.version())
                .balance(toMoneyEmbeddable(credit.balance()))
                .build();
    }

    static CustomerCredit toDomain(CustomerCreditJpaEntity entity) {
        return CustomerCredit.reconstitute(
                entity.getCustomerId(),
                toMoney(entity.getBalance()),
                entity.getVersion() == null ? CustomerCredit.NEW_VERSION : entity.getVersion());
    }

    private static MoneyEmbeddable toMoneyEmbeddable(Money money) {
        return new MoneyEmbeddable(money.amount(), money.currency());
    }

    /** Routes through the canonical constructor, so the stored scale-4 amount is renormalised to the currency. */
    private static Money toMoney(MoneyEmbeddable embeddable) {
        return new Money(embeddable.getAmount(), embeddable.getCurrency());
    }
}