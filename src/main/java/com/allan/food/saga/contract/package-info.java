/**
 * The saga message contract, shared by the orchestrator and every participant.
 *
 * <p>Declared {@code OPEN} because a contract exists to be depended upon: hiding its internals would defeat its
 * purpose. This is the one module in the system that is legitimately shared, and it earns that by containing
 * nothing but records of primitives — no behaviour, no domain types, nothing that couples one context's model
 * to another's.
 */
@ApplicationModule(
        displayName = "Saga Contract",
        type = ApplicationModule.Type.OPEN
)
package com.allan.food.saga.contract;

import org.springframework.modulith.ApplicationModule;