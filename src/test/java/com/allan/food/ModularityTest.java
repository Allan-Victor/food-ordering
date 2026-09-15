package com.allan.food;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Spring Modulith as a boundary linter, and nothing else.
 *
 * <p>We hand-roll events and the outbox deliberately, so none of Modulith's runtime machinery is in use. What
 * this test does buy is verification that the {@code order} module's internals are not reachable from a future
 * sibling module — the check that starts mattering the moment a second bounded context appears.
 */
@DisplayName("Module boundaries")
class ModularityTest {

    @Test
    void modulesAreWellFormed() {
        ApplicationModules.of(OrderServiceApplication.class).verify();
    }

    @Test
    void writeDocumentation() {
        // Generates C4 and PlantUML component diagrams under target/spring-modulith-docs.
        new org.springframework.modulith.docs.Documenter(
                ApplicationModules.of(OrderServiceApplication.class))
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml();
    }
}
