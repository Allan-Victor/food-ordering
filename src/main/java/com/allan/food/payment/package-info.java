/**
 * The payment-bounded context: charging customers and reversing those charges.
 *
 * <p><b>A saga participant, not a service the order context calls.</b> Nothing here is invoked synchronously by
 * ordering. Commands arrive as messages and outcomes leave as messages, which is what allows this context to be
 * unavailable for a minute without ordering failing — the entire availability argument for sagas over
 * distributed transactions.
 *
 * <p><b>Allowed dependencies are declared explicitly</b> so Modulith's verification fails the build if anyone
 * reaches into the order context. In Slice 2 the two live in one JVM and one database, and nothing but this
 * declaration stops a well-meaning shortcut — a join across {@code orders} and {@code payments}, an import of
 * {@code Order} — that would make the Slice 4 split a rewrite. The linter is standing in for the network
 * boundary that does not exist yet.
 */
@ApplicationModule(
        displayName = "Payment",
        allowedDependencies = {"saga.contract"}
)
package com.allan.food.payment;

import org.springframework.modulith.ApplicationModule;