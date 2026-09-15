
/**
 * The restaurant bounded context: deciding whether a placed order can be prepared.
 *
 * <p><b>The pivot participant.</b> Its approval is the point at which this system causes an effect the software
 * cannot reverse — food begins to be prepared — so it is the last step in the order saga whose failure can
 * trigger backward recovery, and the first whose success commits the saga to running forward. Everything about
 * this module's shape follows from that.
 *
 * <p><b>Note what this context owns that the order context only borrows.</b> Ordering holds a read-only
 * {@code Restaurant} replica to confirm names and prices at order time. Here the availability data is
 * authoritative. Two contexts, the same word, opposite ownership — and neither can see the other's version,
 * which is the whole point.
 */

@ApplicationModule(
        displayName = "Restaurant",
        allowedDependencies = {"saga.contract"}
)

package com.allan.food.restaurant;

import org.springframework.modulith.ApplicationModule;