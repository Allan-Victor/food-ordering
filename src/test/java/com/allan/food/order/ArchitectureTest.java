package com.allan.food.order;

import com.allan.food.order.domain.model.entity.Order;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.Architectures;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Executable architecture. Every rule here corresponds to a decision argued for in a class comment; this file
 * is what stops those decisions from quietly eroding.
 *
 * <p><b>Why this matters more than usual for us.</b> Several of our boundaries cannot be expressed in Java.
 * {@code Order.reconstitute} has to be {@code public} because the mapper lives in another package, so the
 * compiler cannot enforce that only the mapper calls it — but ArchUnit can. That is the general pattern: where
 * the language runs out, the build takes over.
 */
@DisplayName("Architecture")
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.allan.food");
    }

    @Test
    @DisplayName("the domain imports no framework")
    void domainIsFrameworkFree() {
        noClasses()
                .that().resideInAPackage("..order.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "jakarta.validation..",
                        "com.fasterxml.jackson..",
                        "lombok..")
                .because("the domain must compile and run with no framework on the classpath — "
                        + "this is the property the whole pure-hexagonal style exists to buy")
                .check(classes);
    }

    @Test
    @DisplayName("the domain does not know the application or the adapters exist")
    void domainDependsOnNothingOutward() {
        noClasses()
                .that().resideInAPackage("..order.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..order.application..", "..order.adapter..")
                .because("the dependency rule points inward, always")
                .check(classes);
    }

    @Test
    @DisplayName("the application layer does not know the adapters exist")
    void applicationDependsOnNoAdapter() {
        noClasses()
                .that().resideInAPackage("..order.application..")
                .should().dependOnClassesThat().resideInAPackage("..order.adapter..")
                .because("adapters plug into ports; the application never reaches out to them")
                .check(classes);
    }

    @Test
    @DisplayName("the layering holds as a whole")
    void onionLayersRespected() {
        Architectures.layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("Domain").definedBy("..order.domain..")
                .layer("Application").definedBy("..order.application..")
                .layer("Adapters").definedBy("..order.adapter..")
                .whereLayer("Adapters").mayNotBeAccessedByAnyLayer()
                .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapters")
                .check(classes);
    }

    @Test
    @DisplayName("only the persistence mapper reconstitutes an aggregate")
    void reconstituteIsForThePersistenceMapperAlone() {
        // The rule the language cannot express. reconstitute skips every business rule by design,
        // so a caller outside the mapper could fabricate an order in any state it liked.
        noClasses()
                .that().resideOutsideOfPackage("..adapter.out.persistence..")
                .should().callMethodWhere(
                        com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                                        com.tngtech.archunit.core.domain.properties.HasName.Predicates.name("reconstitute"))
                                .and(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                                        com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner(
                                                com.tngtech.archunit.base.DescribedPredicate.describe(
                                                        "Order", javaClass -> javaClass.isEquivalentTo(Order.class))))))
                .because("reconstitute trusts its input and runs no invariants; it is public only because "
                        + "the mapper lives in another package, not because it is generally callable")
                .check(classes);
    }

    @Test
    @DisplayName("ports are interfaces")
    void portsAreInterfaces() {
        classes()
                .that().resideInAPackage("..application.port..")
                .and().haveSimpleNameEndingWith("Port")
                .should().beInterfaces()
                .check(classes);
    }

    @Test
    @DisplayName("no JPA entity escapes the persistence adapter")
    void entitiesStayInThePersistenceAdapter() {
        classes()
                .that().areAnnotatedWith(jakarta.persistence.Entity.class)
                .should().resideInAPackage("..adapter.out.persistence..")
                .because("a persistence model that leaks becomes the domain model by accident")
                .check(classes);
    }

    @Test
    @DisplayName("no field injection anywhere")
    void noFieldInjection() {
        noClasses()
                .should().beAnnotatedWith(org.springframework.beans.factory.annotation.Autowired.class)
                .orShould().dependOnClassesThat().haveFullyQualifiedName(
                        "org.springframework.beans.factory.annotation.Autowired")
                .because("constructor injection keeps fields final and dependencies visible")
                .check(classes);
    }

    @Test
    @DisplayName("no context reaches into another's internals")
    void contextsDoNotShareInternals() {
        // The order, payment and restaurant contexts may know only the saga contract and each other's
        // messages. A single import across these boundaries — an Order in the payment context, a
        // shared Money, a repository reaching another context's table — is the change that turns the
        // Slice 4 split from a deployment exercise into a rewrite.
        SlicesRuleDefinition.slices()
                .matching("com.allan.food.(*)..")
                .namingSlices("$1 context")
                .as("Bounded contexts")
                .should().notDependOnEachOther()
                .ignoreDependency(
                        DescribedPredicate.alwaysTrue(),
                        JavaClass.Predicates.resideInAPackage("..saga.contract.."))
                .because("contexts communicate through the saga contract and nothing else")
                .check(classes);
    }
}
