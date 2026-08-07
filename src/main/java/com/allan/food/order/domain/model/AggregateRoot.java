package com.allan.food.order.domain.model;

import java.lang.annotation.*;

/**
 * Marks a class as an aggregate root: the single entry point to its consistency
 * boundary, the only type in that boundary with a repository, and the
 * transaction boundary for every entity and value object it contains.
 *
 * <p>Documentation with a hook for enforcement, not the definition. What makes
 * {@link com.allan.food.order.domain.model.entity.Order} a root is structural — it has a repository, it owns the
 * cross-item invariants, one transaction changes one order. Removing this
 * annotation would not change that; the annotation lets the next reader see it
 * at a glance and lets an ArchUnit rule assert it.
 *
 * <p>An annotation is used rather than a base class so the marker costs nothing:
 * no inheritance slot spent, no unwanted behaviour dragged in (a base-class
 * approach in the reference also brings a public {@code setId}). {@code RUNTIME}
 * retention keeps it visible to architecture tests. At a later step this is
 * swapped for jMolecules' {@code @AggregateRoot}, which pairs the same marker
 * with ready-made enforcement rules.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AggregateRoot {
}
