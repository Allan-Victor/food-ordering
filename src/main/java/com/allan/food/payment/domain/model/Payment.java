package com.allan.food.payment.domain.model;

import com.allan.food.payment.domain.exception.PaymentDomainException;

import java.time.Instant;
import java.util.UUID;

/**
 * A record of one attempt to charge a customer for one order. Aggregate root.
 *
 * <p><b>This aggregate is what makes the participant idempotent</b>, which is the property most saga tutorials
 * omit. At-least-once delivery means {@code ProcessPayment} will arrive twice for the same order, and without a
 * durable record keyed by {@code orderId} the participant has no way to know it has already charged. Persisting
 * the outcome — including failure — is what turns "charge the customer" into an operation that can safely be
 * asked for repeatedly.
 *
 * <p><b>Failures are persisted too, and that is not obvious.</b> The instinct is to record only successes. But
 * a duplicate command for a previously-failed payment must reply {@code PaymentFailed} again rather than
 * re-attempting the charge, and it can only know to do that if the failure was written down. A participant's
 * memory has to cover every outcome it has ever produced, not just the happy ones.
 *
 * <p><b>Keyed by {@code orderId}, uniquely.</b> One order gets at most one payment in this context. That
 * uniqueness is the idempotency key, and it is enforced by a database constraint as well as by lookup, because
 * a check-then-act under concurrency is a race and only the constraint actually closes it.
 *
 * <p>No {@code CreditHistory}. The reference keeps an append-only ledger of debits and credits and validates
 * that it sums to the balance — genuinely good practice, and how real payment systems reconcile. Deferred to
 * Slice 3: it is a second consistency problem stacked on the saga we are here to learn, and adding it now would
 * blur which failures belong to which mechanism.
 */
public final class Payment {

    public static final long NEW_VERSION = -1L;

    private final UUID paymentId;
    private final UUID orderId;
    private final UUID customerId;
    private final Money amount;
    private final Instant createdAt;

    private PaymentStatus status;
    private long version;

    private Payment(UUID paymentId, UUID orderId, UUID customerId, Money amount,
                    PaymentStatus status, Instant createdAt, long version) {
        if (paymentId == null) throw new PaymentDomainException("paymentId is required");
        if (orderId == null) throw new PaymentDomainException("orderId is required");
        if (customerId == null) throw new PaymentDomainException("customerId is required");
        if (amount == null) throw new PaymentDomainException("amount is required");
        if (status == null) throw new PaymentDomainException("status is required");
        if (createdAt == null) throw new PaymentDomainException("createdAt is required");

        this.paymentId = paymentId;
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount;
        this.status = status;
        this.createdAt = createdAt;
        this.version = version;
    }

    /** Records a settled charge. */
    public static Payment completed(UUID orderId, UUID customerId, Money amount, Instant at) {
        return new Payment(UUID.randomUUID(), orderId, customerId, amount,
                PaymentStatus.COMPLETED, at, NEW_VERSION);
    }

    /**
     * Records a refused charge.
     *
     * <p>A first-class outcome with its own factory rather than a flag on the success path, so the two
     * possibilities are equally visible at every call site.
     */
    public static Payment failed(UUID orderId, UUID customerId, Money amount, Instant at) {
        return new Payment(UUID.randomUUID(), orderId, customerId, amount,
                PaymentStatus.FAILED, at, NEW_VERSION);
    }

    /** Rebuilds from storage, running no rules. For the persistence mapper. */
    public static Payment reconstitute(UUID paymentId, UUID orderId, UUID customerId, Money amount,
                                       PaymentStatus status, Instant createdAt, long version) {
        return new Payment(paymentId, orderId, customerId, amount, status, createdAt, version);
    }

    /**
     * Marks this charge reversed.
     *
     * <p>Guarded: only a {@code COMPLETED} payment can be refunded. Refunding a failure would mean handing back
     * money never taken, and the guard is what stops a misrouted or duplicated compensation from doing exactly
     * that.
     *
     * <p>Like {@code Order}, this aggregate is strict — it throws on an illegal transition. The application
     * service is tolerant and checks the status before calling. Same division as in the order saga: the
     * aggregate defends the invariant, the message handler absorbs the transport's noise.
     */
    public void markRefunded() {
        if (status != PaymentStatus.COMPLETED) {
            throw new PaymentDomainException(
                    "Payment %s cannot be refunded from status %s".formatted(paymentId, status));
        }
        status = PaymentStatus.REFUNDED;
    }

    public UUID paymentId()      { return paymentId; }
    public UUID orderId()        { return orderId; }
    public UUID customerId()     { return customerId; }
    public Money amount()        { return amount; }
    public PaymentStatus status(){ return status; }
    public Instant createdAt()   { return createdAt; }
    public long version()        { return version; }

    public boolean isNew() {
        return version == NEW_VERSION;
    }
}
