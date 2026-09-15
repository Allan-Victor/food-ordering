package com.allan.food.restaurant.domain.model;

/**
 * The outcome of one approval decision.
 *
 * <p><b>Two terminal values and nothing else — no intermediate state, and no way back.</b> Contrast
 * {@code PaymentStatus}, which has {@code REFUNDED} because a charge can be undone, and contrast
 * {@code OrderStatus}, which has {@code CANCELLING} because compensation takes time. Neither has an analogue
 * here: an approval is decided in one transaction and stands forever.
 *
 * <p>That absence is the pivot expressed as an enum. When you classify a saga step, this is the question to
 * ask of its participant's state model — if you cannot name the state it would occupy while being undone, it
 * cannot be undone.
 */
public enum ApprovalStatus {

    /** The restaurant accepted. Preparation begins; the saga runs forward from here. */
    APPROVED,

    /** The restaurant refused. Triggers the order saga's only backward-recovery path. */
    REJECTED
}
