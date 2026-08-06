/**
 * Order bounded context.
 *
 * Spring Modulith treats this package as a module. Its root is public API;
 * the sub-packages (domain, application, adapter) are internal. We use this
 * purely as a boundary linter — the ModularityTests verify() call fails the
 * build if another module reaches into these internals.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Order"
)
package com.allan.food.order;
