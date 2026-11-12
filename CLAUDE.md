# CLAUDE.md

> Project memory and working agreement for AI-assisted development on this codebase.
> This file is the single source of truth for architectural decisions, conventions,
> and collaboration rules. Read it before generating or reviewing any code in this repo.

---

## 1. Project Overview

**Name:** Food Ordering System (working title)
**Purpose:** A professional-grade Java reference implementation used both as a deep
learning exercise and as a long-term personal blueprint for Domain-Driven Design (DDD)
application design.

**Primary goals, in order of dependency:**

1. Learn professional, tactical and strategic **Domain-Driven Design**.
2. Implement DDD using **hexagonal / ports-and-adapters architecture**.
3. Master **saga implementation** in Java for distributed transaction management.
4. Build **scalable, event-driven systems**.
   **Operating principle:** Correctness and clarity per class matter more than speed to a
   running system. This project is meant to be referenced for years, not shipped under
   deadline pressure. Do not rush; do not skip design rationale to save time.

**Stack:**

- Java 25
- Spring Boot 4
- Maven (single module, package-based bounded contexts — not multi-module)
- Spring Modulith (used strictly as a boundary **linter**, no runtime machinery)
- jMolecules (architectural annotations for DDD building blocks)
- ArchUnit (structural rule enforcement)
- JUnit 5, Mockito, `@DataJpaTest`, `@WebMvcTest`
  **IDE / tooling:** IntelliJ IDEA. Package-level moves use `Refactor → Move` (F6);
  cross-package import fixes use `Replace in Files`.

---

## 2. Authoritative References

All architectural and tactical decisions are expected to trace back to one of these
sources. When Claude proposes a pattern, it should be able to say which of these
supports it, and should flag when a choice deliberately diverges from one of them.

| Author            | Work                                                   | Used for                                                 |
| ----------------- | ------------------------------------------------------ | -------------------------------------------------------- |
| Eric Evans        | _Domain-Driven Design_                                 | Strategic design, ubiquitous language, bounded contexts  |
| Vaughn Vernon     | _Implementing Domain-Driven Design_                    | Tactical patterns, aggregates, domain events             |
| Chris Richardson  | _Microservices Patterns_                               | Saga pattern, outbox pattern, microservice decomposition |
| Tom Hombergs      | _Get Your Hands Dirty on Clean Architecture_ (buckpal) | Pragmatic hexagonal architecture, ports/adapters wiring  |
| Joshua Bloch      | _Effective Java_                                       | Java idiom, API design, immutability                     |
| Vlad Mihalcea     | _High-Performance Java Persistence_                    | JPA/Hibernate correctness, optimistic locking, mapping   |
| Mauricio Aniche   | _Effective Software Testing_                           | Test design and structure                                |
| Vladimir Khorikov | _Unit Testing: Principles, Practices, and Patterns_    | Unit vs. integration boundaries, test doubles            |

**Secondary/comparison reference:** Ali Gelenler's Udemy course and companion repo
(`agelenler/food-ordering-system`) is used as a parallel implementation to compare
against — not copied from. Verdict on this repo (established by direct inspection,
not hype): it is a clean **pedagogical** reference (clear ports/adapters split,
hand-rolled saga/outbox, domain-core-depends-on-nothing structure) but is **not**
an industry-standard production artifact — it's a course companion repo (small
commit history, JDK 17, teaching-oriented). Treat it as a map to check work
against, not a target to imitate uncritically.

Each time Claude introduces a pattern, it should implicitly or explicitly anchor it
to this table rather than inventing conventions from general training data.

---

## 3. Architectural Decisions (Locked)

These are settled decisions. Do not silently deviate from them; if a change seems
warranted, raise it explicitly rather than applying it.

- **Module structure:** Single Maven module, package-based bounded contexts (e.g.
  `order`, `payment`, `restaurant`), not Gelenler's multi-module layout.
- **Spring Modulith:** Boundary linter only. No `@ApplicationModuleListener`
  runtime machinery, no Modulith event publication registry — those are
  hand-rolled instead (see below), and Modulith is reserved for its own
  comparison branch (`slice3/outbox-modulith`).
- **Hexagonal architecture:** Pure. Zero framework annotations on the domain
  model. JPA entities and mappers live exclusively in the adapter layer, never
  on domain classes. This is a conscious trade-off (more mapping code) chosen
  over the "pragmatic" annotated-domain-entity approach; the pragmatic
  alternative is tracked separately on `arch/pragmatic-persistence` for
  comparison, never merged into `main`.
- **Domain events & outbox:** Hand-rolled, not framework-provided. Events are
  published via Spring's `ApplicationEventPublisher` with
  `@TransactionalEventListener(phase = AFTER_COMMIT)` for in-process saga
  wiring. A transactional outbox is layered in at Slice 3.
- **Identity:** Raw `UUID`, not wrapped identity value types (e.g. no
  `OrderId` wrapper class). This is a deliberate simplicity trade-off.
- **Construction:** Static factory methods (`Order.create`, `Order.reconstitute`)
  preferred over builders, except where parameter count/optionality genuinely
  justifies a builder.
- **Saga style:** Orchestrated saga with a hand-rolled state machine
  (`OrderSaga` orchestrator + participant services), not choreography, and not
  (yet) a framework like Temporal/Axon/Spring State Machine — those are
  comparison branches, not the mainline implementation.
- **Structural enforcement:** jMolecules annotations express DDD building
  blocks (`@AggregateRoot`, `@ValueObject`, etc. as applicable) and ArchUnit
  tests enforce dependency-direction and layering rules at build time.
- **Test currency:** UGX is the default currency used in tests specifically
  because it has zero minor units, which forces correct exercise of
  `Currency.getDefaultFractionDigits()` and prevents accidental
  cents/decimal assumptions from leaking into domain logic.

---

## 4. Coding Conventions (Locked)

### Domain layer

- Domain classes are **final** by default. Only remove `final` when a class is
  deliberately designed for inheritance, and document why.
- **Records** for value objects and immutable data carriers; **classes** for
  entities and aggregates (which have identity and lifecycle).
- **Record-style accessors** throughout the domain (`orderId()`, not
  `getOrderId()` / `id()`), even on non-record entity classes, for
  consistency with the records.
- Validate all invariants at construction. Defensive copies on the way in;
  unmodifiable views on the way out (e.g. `List.copyOf`, `Collections.unmodifiableList`).
- Distinguish **creation contracts** from **reconstitution contracts**
  explicitly: a factory like `Order.create(...)` derives and enforces
  invariants (e.g. computing and validating totals); `Order.reconstitute(...)`
  trusts persisted state and does not recompute derived values. These are not
  interchangeable and should not be collapsed into one method.
- Respect the dependency direction even when it's inconvenient: e.g.
  package-private factory methods (`OrderItem.of`) and nested types
  (`PersistedItem`, `ConfirmedItem` nested inside `Order`) exist specifically
  to avoid exposing construction paths that would let outer layers violate
  the domain's invariants.
- Domain events are **records implementing a sealed interface**
  (`sealed interface DomainEvent permits OrderCreatedEvent, OrderPaidEvent, ...`).
  Use `Instant` for timestamps, not `ZonedDateTime`, inside events.
- Prices and monetary amounts are **never client-supplied**. Aggregates that
  need pricing hold a read-only, server-populated replica (e.g. `Restaurant`
  holding a map-indexed product catalog with an O(1) `requireProduct` lookup
  that throws on an unknown product) rather than trusting inbound DTOs.
- Sealed **command hierarchies** (sealed interface + exhaustive `switch`) are
  appropriate for saga/orchestration-level commands, where a fixed, known set
  of transitions exists. Plain input-port DTOs remain appropriate at the
  ordinary use-case/input-port layer, where the port itself is the contract.

### Persistence

- `@Version` fields are always the **`Long` wrapper type**, never the
  primitive `long`, so Hibernate can distinguish "unset" from "zero."
- Use an explicit sentinel (`NEW_VERSION = -1L`) to distinguish a
  newly-created aggregate from a reconstituted one before the first save.
- JPA entities, embeddables, and mappers belong only in the adapter layer.
  A JPA entity is not a domain object and must not be treated as one, even
  if the temptation to "just add `@Entity`" appears.

### Application / ports

- **Package-private** service implementations; **public** interface ports.
  The port is the contract; the implementation is an internal detail.
- **Constructor injection** with **`final`** fields throughout — no field
  injection, no setter injection.
- **No Lombok on domain classes.** Lombok is permitted only in the adapter
  layer (e.g. on JPA entities), never on domain entities, value objects, or
  application services where it would obscure invariants.

### Documentation

- Every class gets **thorough Javadoc** written to a "future-you reads this
  cold" standard. Each non-obvious design decision documents:
  1. The choice actually made.
  2. The alternative ("what you'll see elsewhere" / in other codebases or the
     comparison repo).
  3. The rationale for diverging from that alternative here.
- Javadoc is treated as part of the deliverable, not an afterthought — it is
  what makes this codebase usable as a reference years from now.

---

## 5. Testing Standards

- **Domain unit tests** — no Spring context, no mocks needed unless testing
  collaboration; exercise invariants directly.
- **Application/use-case tests** — Mockito-mocked ports, verifying
  orchestration logic in isolation from infrastructure.
- **Persistence adapter tests** — `@DataJpaTest`, verifying mapping and
  query correctness against a real (typically embedded/test) database.
- **Web adapter tests** — `@WebMvcTest`, verifying HTTP contract, request
  validation, and error mapping (RFC 9457 `ProblemDetail`) in isolation from
  the rest of the stack.
- **Architecture tests** — ArchUnit rules enforcing package dependency
  direction, layering, and any structural rule implied by Section 3/4 above
  (e.g. "no JPA annotations in the domain package," "no field injection").
- Follow Khorikov's unit-vs-integration boundary discipline: a test that
  needs Spring context or a database is not a unit test — label and locate
  it accordingly.
- New tactical patterns or invariants should get **both** a domain test and,
  where relevant, an ArchUnit rule that would catch a regression
  structurally, not just behaviorally.

---

## 6. Build Order & Roadmap

Work proceeds in **runnable vertical slices**, ordered by goal dependency, so
something runs end-to-end before the next architectural layer is added.
Never build broad (many modules half-finished) before something runs.

| Slice   | Scope                                                                                                                                                                                                                                                                            | Status      |
| ------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------- |
| Slice 1 | Domain layer, application services, persistence adapter, web adapter, ArchUnit tests, Modulith boundary verification, RFC 9457 error handling                                                                                                                                    | ✅ Complete |
| Slice 2 | Orchestrated saga: `OrderSaga` orchestrator, `PaymentService` & `RestaurantApprovalService` participants, six saga ports, seven in-process adapters (`ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)`), payment/restaurant persistence, full test suite | ✅ Complete |
| Slice 3 | Transactional outbox (hand-rolled and Modulith variants)                                                                                                                                                                                                                         | Planned     |
| Slice 4 | Microservices deployment slice                                                                                                                                                                                                                                                   | Planned     |

### Comparison branches

Created from the `main` commit once saga tests are complete. Each branch is a
**runnable** alternative implementation plus a structured `COMPARISON.md`
documenting: what changed, what the approach buys, what it costs, and when a
real team would choose it over the mainline approach.

| Branch                       | Focus                                                                         |
| ---------------------------- | ----------------------------------------------------------------------------- |
| `saga/temporal`              | Primary comparison — Temporal Java SDK replacing the hand-rolled orchestrator |
| `saga/spring-state-machine`  | Spring State Machine as the saga engine                                       |
| `saga/axon`                  | Axon Framework as the saga/event-sourcing engine                              |
| `arch/pragmatic-persistence` | Annotated domain entities vs. pure hexagonal split                            |
| `slice3/outbox-handrolled`   | Transactional outbox, hand-rolled                                             |
| `slice3/outbox-modulith`     | Transactional outbox via Spring Modulith's event publication registry         |
| `slice4/microservices`       | Microservices deployment slice                                                |

`COMPARISON.md` is mandatory for every comparison branch — a branch without one
is considered incomplete, regardless of whether the code runs.

---

## 7. Collaboration Rules for Claude

When working in this repository, Claude should:

1. **Default to teaching, not just producing.** Explain _why_, anchored to
   Section 2's authors, not just _what_. This is a learning project first.
2. **Never silently violate Section 3 or 4.** If a request conflicts with a
   locked decision or convention, say so explicitly before proceeding, and
   propose the compliant alternative.
3. **Prefer correctness and completeness over speed.** Do not truncate
   Javadoc, skip tests, or abbreviate a class "to save time" unless
   explicitly asked to sketch/draft.
4. **Distinguish mainline work from comparison-branch work.** Code intended
   for `main` must follow Section 3's locked hexagonal/DDD decisions exactly.
   Code for a comparison branch (Section 6) is expected to diverge — that's
   the point — but must still get a `COMPARISON.md`.
5. **Flag when the Gelenler course repo (or any secondary reference) is being
   used to double-check an approach**, and be explicit about where this
   codebase intentionally diverges and why.
6. **When adding a new pattern not yet covered here**, propose it, get
   confirmation, and then this file should be updated to record it as a
   locked decision — CLAUDE.md is a living record of settled decisions, not
   a static spec written once.
7. **Ask before assuming scope.** If a request is ambiguous between "build
   this on `main`" and "sketch this as a comparison branch," ask, since the
   two have different standards of conformance.
   8. **Default code output to inline display, not files.** When drafting or
      reviewing code collaboratively in a chat session, show it in the
      conversation by default rather than writing it to a downloadable file —
      reserve a file for when a file is actually what's being asked for (a
      deliverable meant to be run or handed off as-is, or an explicit request).
      Keeps drafts reviewable and revisable in place, consistent with Rule 1's
      "teaching, not just producing."

---

## 8. Glossary (Project-Specific Usage)

- **Slice** — A vertical, runnable increment of functionality spanning all
  architectural layers (domain → application → adapters), not a horizontal
  layer-by-layer build-out.
- **Pure hexagonal** — Zero framework annotations in the domain model;
  persistence concerns fully isolated in adapters via separate entities and
  mappers. (Contrast: "pragmatic hexagonal," tracked only on
  `arch/pragmatic-persistence`.)
- **Reconstitution** — Rebuilding an aggregate from persisted state, trusting
  stored invariants rather than recomputing them (contrast with `create`,
  which derives and enforces invariants fresh).
- **Comparison branch** — A fully runnable alternative implementation of an
  already-solved problem, built specifically to document trade-offs versus
  the mainline approach, never merged into `main`.

---

_This file should be kept up to date as decisions are made or revised. Treat
it as the persistent memory of the project's architecture and conventions —
update it in the same session a new decision is finalized, not after the fact._
