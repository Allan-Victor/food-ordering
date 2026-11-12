

/**
 * Saga vocabulary for the Temporal comparison branch: workflow and activity contracts only, no business logic.
 *
 * <p><b>{@code saga.contract}'s sibling, not {@code order}'s subordinate.</b> This module plays the same role
 * for this branch's transport that {@code saga.contract} plays for the message-based one — shared shape,
 * no implementation. {@code OrderSagaWorkflow}'s class Javadoc has the full rationale for the split.
 *
 * <p><b>Allowed dependencies are declared explicitly, and deliberately narrow.</b> Only {@code saga.contract}
 * — the same leaf-dependency posture {@code saga.contract} itself holds. Every implementation of an interface
 * declared here reaches back into this package, never the other way around; if this module ever needed to
 * depend on {@code order}, {@code payment}, or {@code restaurant} directly, that would be the signal the
 * contract-here/implementation-there split had failed and business logic had leaked into the vocabulary layer.
 */
@ApplicationModule(
        displayName = "Temporal Orchestrator (Comparison branch)",
        allowedDependencies = {"saga.contract"}
)
package com.allan.food.temporal;

import org.springframework.modulith.ApplicationModule;