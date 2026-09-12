# Spring Modulith

A reference guide to Spring Modulith: what it checks, how it enforces module boundaries, and how it makes cross-module communication both decoupled and durable.

## What Spring Modulith Is

Spring Modulith is a library for building and verifying **modular monoliths** in Spring Boot. It doesn't change how you write Spring code — you still use `@Service`, `@Repository`, `@RestController`, and ordinary packages. What it adds is:

- **Structural verification** — a test that inspects your package structure and fails the build if modules are tangled or reaching into each other's internals.
- **Event-based decoupling** — a way for one module to announce that something happened without knowing who (if anyone) is listening.
- **Durable event delivery** — a transactional outbox implementation, built in, so published events survive crashes and get retried.

None of this requires a framework migration or a rewrite. It works with the package structure you already have, and each capability can be adopted incrementally.

## Requirements

- Spring Boot 3.x or 4.x
- Spring Modulith 1.x or 2.x (`spring-modulith-starter-core`, plus `spring-modulith-starter-jdbc` / `-jpa` / `-mongodb` if you want durable events)
- Java 17+

## Core Concepts

### Application Modules

A Spring Modulith **module** is simply a top-level package directly under your application's main package. If your main class lives in `com.example.app`, then `com.example.app.orders` and `com.example.app.shipping` are each treated as a module — no annotation required.

Modulith inspects this structure via:

```java
var modules = ApplicationModules.of(Application.class);
```

This gives you a queryable model of your application's module structure, which the verification API and the (optional) documentation generator both build on.

### Verification: `ApplicationModules.verify()`

```java
// src/test/java/com/example/app/ModularityTests.java
package com.example.app;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    @Test
    void verifiesModularStructure() {
        ApplicationModules.of(Application.class).verify();
    }

}
```

This is **pure static analysis**. It inspects packages and types directly — no `@SpringBootTest`, no Spring context, no database connection, nothing running. That's deliberate: it keeps the architecture check fast and independent of whatever else is happening in your test suite, so it's cheap enough to run on every build.

`verify()` performs two independent checks:

| Check | What it catches | When it activates |
|---|---|---|
| **Cycle detection** | Two or more modules depending on each other, directly or transitively | Immediately — no boundaries or annotations needed |
| **Visibility enforcement** | A module reaching into another module's *internal* types | Only once a module has internals to protect |

A cycle looks like this when it fails:

```
Cycle detected: Slice moduleB ->
                Slice moduleA ->
                Slice moduleB
```

This is the single most useful thing Modulith does on day one: it tells you your modules are tangled *before* you've drawn a single boundary. Cycle detection is free. Visibility enforcement has to be earned by defining internals — covered next.

### Visibility: Two Levels of Enforcement

**Level 1 — Package-private (compiler-enforced).** The smallest possible boundary: drop the `public` keyword from a type. The Java compiler itself then refuses any import from outside that type's own package.

```
error: SomeRepository is not public in com.example.app.moduleA;
       cannot be accessed from outside package
```

This works for small modules but doesn't scale: once a module needs its own sub-packages (persistence, web, etc.), a service in the module's root package can no longer see a package-private type living one level down.

**Level 2 — Internal sub-packages (Modulith-enforced).** Modulith's richer rule: *anything in a sub-package of a module is automatically internal.* Only types sitting in the module's top-level (root) package count as its public API. Conventional sub-package names — `internal`, `persistence`, `web`, or anything else — are all treated identically: none of them count as API surface.

This means:
- Cross-module references to a module's **root-level** types are fine.
- Cross-module references into a module's **sub-packages** fail verification — even though the code compiles cleanly, since both types may be `public`.

```
- Module 'moduleB' depends on non-exposed type
  com.example.app.moduleA.internal.SomeRepository
  within module 'moduleA'
```

If a module ever needs to expose a **second** public surface distinct from its root package, `@org.springframework.modulith.NamedInterface` is the documented escape hatch.

**Important distinction:** "internal" here is about JVM-level visibility between modules — which Java types one module is allowed to reference inside another. It has nothing to do with HTTP. A `@RestController` placed in an `internal` sub-package is still fully reachable over HTTP the moment the application starts; Modulith's internal/API distinction only governs direct Java imports between modules, not the web layer.

The two enforcement levels are complementary, not redundant: the compiler catches the obvious violations at the keystroke; Modulith's verification test catches the architectural reach-arounds the compiler can't see (two `public` types, two `public` packages, a perfectly legal import that still violates the intended boundary).

### Breaking Cycles with Domain Events

Visibility rules control **who is allowed to reach for what** — they don't change **which direction a call goes**. If two modules call each other directly, hiding internals won't fix the cycle; you have to flip one of the calls.

The pattern: instead of Module A directly calling Module B, Module A **publishes a domain event** and lets anyone interested listen for it. Module A no longer needs to know Module B exists.

**Publishing side** — swap a direct dependency for `ApplicationEventPublisher`:

```java
@Service
public class ServiceA {

    private final ApplicationEventPublisher events;

    public ServiceA(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Transactional
    public void doSomething() {
        // ... perform the write ...
        events.publishEvent(new SomethingHappened(/* ... */));
    }
}
```

**Listening side** — annotate a method with `@ApplicationModuleListener`:

```java
@Service
public class ServiceB {

    @ApplicationModuleListener
    public void onSomethingHappened(SomethingHappened event) {
        // react to the event
    }
}
```

`@ApplicationModuleListener` is a Modulith-supplied meta-annotation bundling three things:

- `@Async`
- `@TransactionalEventListener(phase = AFTER_COMMIT)`
- `@Transactional(propagation = REQUIRES_NEW)`

In practice: the listener fires **after** the publisher's transaction commits, on a **different thread**, inside its **own transaction**. That gives you three properties a direct call can't:

- **Failure isolation** — if the listener throws, the publisher's write still succeeds.
- **Latency isolation** — a slow listener doesn't slow down the publisher's response time.
- **Open extension** — adding a new listener is a new file in a new module; the publisher is never edited.

The dependency arrow now points one way only (listener → publisher's event type), which is what resolves the cycle that `verify()` was flagging.

*Note:* `@ApplicationModuleListener` lives in `spring-modulith-events-api`, which isn't pulled in by `spring-modulith-starter-core` alone — add `spring-modulith-starter-jdbc` (or `-jpa`/`-mongodb`) to get it, along with the event publication registry described next. Without Modulith, plain `@TransactionalEventListener(phase = AFTER_COMMIT)` gets you most of the same effect, minus the async dispatch and the registry.

### Durability: The Event Publication Registry

Async, transactional-boundary-crossing events introduce a new failure mode: what if the process crashes *between* the publisher's commit and the listener firing? The business write is durable; the event is not — unless something persists it.

That's what the **event publication registry** does. With a Modulith events starter (`-jdbc`, `-jpa`, or `-mongodb`) on the classpath:

1. Every published event is persisted to a database table **in the same transaction** as the business write — this is the transactional outbox pattern.
2. Once a listener completes successfully, Modulith marks that event as delivered.
3. If a listener never runs (crash, deploy, timeout), the event remains incomplete and is retried.

No application code changes to enable this — adding the dependency turns it on.

Setup required:

- A schema for the event publication table (the starter ships SQL runnable via Flyway, Liquibase, or a one-off `schema.sql`).
- Optionally, replay of incomplete events on startup:

  ```properties
  spring.modulith.events.republish-outstanding-events-on-restart=true
  ```

The result: events are published transactionally with the business write, listeners run asynchronously and in isolation, and the registry guarantees at-least-once delivery even across a process restart — all with application code barely different from a plain event listener.

### Inspecting the Registry

The registry lives in a standard table (`event_publication` by default) with columns including `completion_date`, `status`, and `completion_attempts`. A row with a null `completion_date` and a non-`COMPLETED` status represents an event still awaiting successful delivery — useful for diagnostics or manual intervention during development.

## Summary

| Capability | Mechanism | Enforced by |
|---|---|---|
| Detect tangled modules | Dependency cycle analysis | `ApplicationModules.verify()` — active immediately |
| Protect a module's internals | Package-private types | The Java compiler |
| Protect a module's internals at scale | `internal`/sub-package convention, `@NamedInterface` | `ApplicationModules.verify()` |
| Decouple modules | Domain events + `ApplicationEventPublisher` | Application code / design |
| Isolate failure and latency | `@ApplicationModuleListener` | Spring Modulith |
| Guarantee delivery | Event publication registry (transactional outbox) | Spring Modulith (`-jdbc`/`-jpa`/`-mongodb` starter) |

## Resources

- [Spring Modulith reference docs](https://docs.spring.io/spring-modulith/reference/)
- [Spring Modulith on GitHub](https://github.com/spring-projects/spring-modulith)
- [Official examples](https://github.com/spring-projects/spring-modulith/tree/main/spring-modulith-examples)
