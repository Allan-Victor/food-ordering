package com.allan.food.order.domain.model.valueobject;

/**
 * The lifecycle states of an {@link com.allan.food.order.domain.model.entity.Order}.
 *
 * <pre>
 *   PENDING ──pay──▶ PAID ──approve──▶ APPROVED
 *      │              │
 *   cancel        initCancel
 *      │              │
 *      │              ▼
 *      │          CANCELLING ──cancel──▶ CANCELLED
 *      └──────────────────────────────▶ CANCELLED
 * </pre>
 *
 * <p><strong>Deliberately behaviour-free.</strong> The enum is vocabulary; the
 * legal transitions live on {@link com.allan.food.order.domain.model.entity.Order}, guarded inside each intent-named
 * method ({@code pay}, {@code approve}, {@code cancel}). Encoding the transition
 * table on the enum is an alternative that reads well past roughly eight states,
 * but at five it adds indirection and, more importantly, moves a rule out of the
 * aggregate that owns it. The aggregate keeps its own rules.
 *
 * <p>{@code CANCELLING} is a <em>semantic lock</em>: the compensation-in-progress
 * state that tells the rest of the system a refund is outstanding and the order
 * must not yet be treated as settled.
 */
public enum OrderStatus {
    PENDING,
    PAID,
    APPROVED,
    CANCELLING,
    CANCELLED
}
