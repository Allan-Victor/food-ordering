package com.allan.food.order.domain.model;

import java.lang.annotation.*;

/**
 * Marks an aggregate root: the sole entry point to its consistency
 * boundary, the only type with a repository, and the transaction
 * boundary for everything inside it.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AggregateRoot {
}
