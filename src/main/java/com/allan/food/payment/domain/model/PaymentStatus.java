package com.allan.food.payment.domain.model;

/**
 * The lifecycle of a single payment.
 *
 * <p>Vocabulary only, no transition logic — guards live on {@link Payment}, exactly as {@code OrderStatus}
 * keeps its guards on {@code Order}.
 *
 * <p>Note there is no {@code PENDING}: in this context a charge either settles or does not, synchronously,
 * within one transaction. A real provider integration would add {@code PENDING} and an inbound webhook, which
 * would make payment a saga of its own nested inside this one. Out of scope, and worth knowing it is the usual
 * next complication.
 */
public enum PaymentStatus {

    /** The customer was charged and their balance debited. */
    COMPLETED,

    /** The charge was refused — almost always insufficient balance. Terminal; nothing to compensate. */
    FAILED,

    /** A completed charge was reversed by a compensating transaction and the balance restored. */
    REFUNDED
}
