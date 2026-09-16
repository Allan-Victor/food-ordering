package com.allan.food.payment.domain.model;

import com.allan.food.payment.domain.exception.PaymentDomainException;

import java.util.UUID;

/**
 * A customer's available balance in this context. Aggregate root.
 *
 * <p><b>Why the payment participant owns real money rather than stubbing failure.</b> A saga is only worth
 * building because steps genuinely fail, and a step that fails on a coin flip teaches nothing. An actual
 * balance gives the compensatable step a real reason to refuse and, more importantly, makes compensation
 * <i>observable</i>: after a restaurant rejection the balance goes back up, and you can see that the refund
 * did something rather than merely logging that it happened. Adopted from the reference, which is right about
 * this.
 *
 * <p><b>The invariant: a balance may never go negative.</b> Enforced structurally rather than by a check —
 * {@link Money}'s constructor rejects negative amounts, so an overdrawn balance cannot be represented at all.
 * {@link #debit} therefore tests affordability <i>before</i> subtracting, and the two guards are complementary:
 * the domain rule produces a meaningful message, the value object makes violation unrepresentable if the rule
 * is ever removed.
 *
 * <p><b>Its own consistency boundary, separate from {@link Payment}.</b> Two aggregates changed in one
 * transaction is a deviation from Vernon's rule of one aggregate per transaction, and it is deliberate: within
 * a single context sharing one database, the atomicity is real and free. The rule exists to stop you assuming
 * atomicity <i>across</i> boundaries where it does not hold — which is precisely why the order and payment
 * contexts are coordinated by a saga rather than a transaction.
 *
 * <p>Carries a {@code version} for the same reason {@code Order} does, and here the race is more obvious: two
 * concurrent debits against one balance is the textbook lost update.
 */
public final class CustomerCredit {

    /** See {@code Order.NEW_VERSION} — the same sentinel convention, for the same reason. */
    public static final long NEW_VERSION = -1L;

    private final UUID customerId;
    private Money balance;
    private long version;

    private CustomerCredit(UUID customerId, Money balance, long version) {
        if (customerId == null) {
            throw new PaymentDomainException("customerId is required");
        }
        if (balance == null) {
            throw new PaymentDomainException("balance is required");
        }
        this.customerId = customerId;
        this.balance = balance;
        this.version = version;
    }

    /** Opens a new credit record. */
    public static CustomerCredit open(UUID customerId, Money openingBalance) {
        return new CustomerCredit(customerId, openingBalance, NEW_VERSION);
    }

    /** Rebuilds from storage, running no rules. For the persistence mapper. */
    public static CustomerCredit reconstitute(UUID customerId, Money balance, long version) {
        return new CustomerCredit(customerId, balance, version);
    }

    /**
     * Whether this balance covers the given amount.
     *
     * <p>Exposed so the application service can decide to reply {@code PaymentFailed} rather than catching an
     * exception to control flow. Effective Java Item 69: exceptions are for exceptional conditions, and a
     * customer who cannot afford an order is an entirely ordinary outcome the saga is built to handle.
     */
    public boolean canAfford(Money amount) {
        return !balance.isLessThan(amount);
    }

    /**
     * Deducts an amount.
     *
     * @throws PaymentDomainException if the balance does not cover it — a guard against a caller that skipped
     *         {@link #canAfford}, not the normal path for an unaffordable order
     */
    public void debit(Money amount) {
        if (!canAfford(amount)) {
            throw new PaymentDomainException(
                    "Customer %s has %s, cannot afford %s".formatted(customerId, balance, amount));
        }
        balance = balance.subtract(amount);
    }

    /**
     * Restores an amount. The compensating operation for {@link #debit}.
     *
     * <p>Unguarded, and necessarily so: a compensating transaction must always be able to succeed. If crediting
     * money back could be refused, the saga would have no way home and the step it compensates would never have
     * qualified as compensatable.
     */
    public void credit(Money amount) {
        balance = balance.add(amount);
    }

    public UUID customerId() {
        return customerId;
    }

    public Money balance() {
        return balance;
    }

    public long version() {
        return version;
    }

    public boolean isNew() {
        return version == NEW_VERSION;
    }
}
