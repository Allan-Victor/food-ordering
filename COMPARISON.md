# COMPARISON.md — `saga/temporal`

> Required by `CLAUDE.md` Section 6 for every comparison branch: what changed, what the
> approach buys, what it costs, and when a real team would choose it over the mainline
> approach. This document covers the Temporal Java SDK replacing `main`'s hand-rolled,
> event-based orchestrator (`OrderSaga` + six saga ports + seven in-process adapters).

---

## 1. What changed

| Concern                     | `main` (hand-rolled)                                                                                                     | `saga/temporal`                                                                                                           |
| ---------------------------- | -------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| Orchestrator                | `OrderSaga` — a single class with `@TransactionalEventListener(AFTER_COMMIT)` handlers per saga event                    | `OrderSagaWorkflowImpl` — a plain POJO `run`/`pivot` method pair, instantiated per-execution by the Temporal worker       |
| Trigger                     | `OrderSaga.onOrderCreated` listens for `OrderCreatedEvent`                                                               | `OrderSagaWorkflowStarter` listens for the same `OrderCreatedEvent`, then calls `WorkflowClient.start`                    |
| Inter-context contract      | `saga.contract.SagaContract` — commands/replies (`ProcessPayment`, `PaymentCompleted`, `PaymentFailed`, ...) carried as Spring application events | `temporal.{OrderSagaWorkflow, OrderStateActivities, PaymentActivities, RestaurantActivities}` — Temporal `@ActivityInterface`/`@WorkflowInterface` contracts, called as synchronous method invocations proxied by Temporal |
| Participant business logic  | `PaymentService`, `RestaurantApprovalService`, `Order`'s transition methods                                              | **Unchanged** — the same three collaborators, now invoked from `*ActivitiesImpl` adapters instead of event listeners     |
| State persistence           | Each step's outcome is persisted explicitly by the handler (`saveOrderPort.save(order)`), saga progress is implicit in `OrderStatus` | Same per-step persistence via `OrderStateActivities` (`markPaid`, `markApproved`, `markCancelling`, `markCancelled`); saga *progress itself* is additionally durable inside Temporal's event history |
| Compensation                | `onOrderRejected` → explicit refund call → `onPaymentRefunded`                                                            | `pivot`'s `Rejected` branch: `markCancelling` → `refundPayment` → `markCancelled`, inline in one method, same one-directional shape |
| Retry on transient failure  | None — a failed listener either throws (visible as an error) or silently requires redelivery infrastructure not present on `main` | Automatic, configured per-activity via `ActivityOptions` (currently just a 30s `StartToCloseTimeout`; no explicit retry policy override, so Temporal's default exponential-backoff retry applies) |
| New infrastructure          | None — runs inside the same Spring process, no extra moving parts                                                        | A Temporal server (`temporal server start-dev` locally) plus a `WorkerFactory`/`Worker` registered in `TemporalConfig` — a second process the app now depends on to make any progress |
| Dependency footprint        | None beyond Spring itself                                                                                                | `io.temporal:temporal-sdk:1.31.0` (runtime) and `io.temporal:temporal-testing:1.31.0` (test scope, currently unused — see §3) |
| Test coverage               | `OrderSagaTest`, `PaymentServiceTest`, `RestaurantApprovalServiceTest`, `PaymentPersistenceAdapterTest`, `OrderSagaIntegrationTest` — ~1,159 lines covering the saga end to end | **Deleted, not replaced.** No workflow/activity test exists on this branch (see §3) |
| Module boundary enforcement | `ArchitectureTest.contextsDoNotShareInternals` exempts only `..saga.contract..`                                          | Same test, additionally exempts `..temporal..`; `payment`/`restaurant` `package-info.java` declare `temporal` as an allowed dependency |

The participant services and the domain model are untouched byte-for-byte in intent — this
branch only swaps *how steps are sequenced and made durable*, exactly the scope
`CLAUDE.md` Section 3 reserves for comparison branches ("not (yet) a framework like
Temporal/Axon/Spring State Machine").

---

## 2. What Temporal buys

- **Durable execution without hand-rolled persistence.** `OrderSaga` relies on `OrderStatus`
  in the database to reconstruct "where was this saga when the process died?" — correct, but
  implicit, and it only works because every transition was designed to be resumable from
  that one column. Temporal's workflow history *is* that resumption state, replayed
  automatically; nothing bespoke has to be designed for crash recovery.
- **Automatic retry for transient technical failures.** On `main`, a thrown exception from
  `PaymentService` during saga processing has no built-in retry — it either propagates as an
  error or relies on message-broker redelivery that doesn't exist in-process. Every
  `ActivityOptions`-wrapped activity here gets Temporal's retry policy for free, without a
  single line of retry logic written by hand.
- **Visibility into in-flight sagas.** Temporal's Web UI and `tctl`/`temporal` CLI show every
  running and completed workflow, its current step, and its full event history — `main`
  offers no equivalent without reading `OrderStatus` plus application logs and inferring the
  rest.
- **A true terminal result for the caller.** `main`'s saga is fire-and-forget: every listener
  commits and returns, nobody awaits the outcome. `OrderSagaWorkflow.run` returns `Outcome`,
  so a caller (not currently exercised by this branch, but structurally available) can block
  on `WorkflowClient` and get `APPROVED`/`CANCELLED` back directly, rather than polling
  `OrderStatus`.
- **The whole saga reads as one function again, with less plumbing.** `OrderSagaWorkflowImpl.run`/`pivot`
  is a straight-line `switch` over sealed results — arguably *more* legible than `main`'s six
  separately-triggered `@TransactionalEventListener` methods, because Temporal's "durable
  function" model removes the need to split the flow across event handlers just to get
  resumability.

---

## 3. What it costs

- **A second infrastructure dependency the app cannot run without.** `TemporalConfig` wires
  `WorkflowServiceStubs.newLocalServiceStubs()` unconditionally — there is no mode in which
  this branch's saga progresses without a reachable Temporal server. `main` has zero such
  requirement; the whole saga runs inside the one JVM already needed for anything else.
- **Test coverage was deleted, not replaced — this is the branch's biggest honest gap.**
  `main` carries ~1,159 lines of saga-focused tests (`OrderSagaTest`,
  `OrderSagaIntegrationTest`, `PaymentServiceTest`, `RestaurantApprovalServiceTest`,
  `PaymentPersistenceAdapterTest`). All were removed on this branch, and nothing — not a
  `TestWorkflowEnvironment`-based workflow test, not an activity unit test — was written to
  replace them, even though `temporal-testing` is already a declared test-scope dependency
  in `pom.xml` and unused. The "all tests pass" result for this branch reflects a smaller
  suite, not equivalent confidence. Any team adopting this branch as-is must treat writing
  `OrderSagaWorkflowImplTest` (via Temporal's `TestWorkflowRule`) as a blocking follow-up,
  not an optional one.
- **A second place business rules can live, with a new discipline to enforce.** Workflow
  code must stay deterministic and side-effect-free (`OrderSagaWorkflowImpl`'s own Javadoc
  calls this out) — a constraint `main`'s orchestrator never had to satisfy, since Spring
  transactions already made inline I/O safe there. Every future change to the workflow has
  to be checked against this rule by hand; nothing in this codebase (ArchUnit or otherwise)
  currently enforces "no I/O inside workflow code" structurally.
- **Operational surface area.** A Temporal server is a stateful service with its own
  persistence (defaults to an embedded SQLite in dev mode, Cassandra/MySQL/PostgreSQL in
  production) that now needs deploying, monitoring, backing up, and upgrading — none of
  which `main`'s design requires at all.
- **A steeper onboarding curve.** Reading `main`'s `OrderSaga` requires knowing Spring events
  and transaction phases. Reading this branch requires knowing those *plus* Temporal's
  worker/activity/workflow execution model, determinism rules, and `ActivityOptions`
  semantics — a second framework's mental model layered on top of the first.
- **No generic multi-step compensation machinery used, by design** (see
  `OrderSagaWorkflowImpl`'s Javadoc) — meaning Temporal's own `Saga` helper class, one of its
  selling points for complex sagas, isn't exercised here at all. This saga is too simple
  (one pivot, one backward path) to showcase where Temporal's compensation tooling actually
  earns its complexity budget.

---

## 4. When a real team would choose this over the mainline approach

Choose **Temporal** when:

- The saga has **many steps or branches**, not two — once a flow has five, six, or more
  participants with partial-failure handling at each, Temporal's durable-execution model and
  built-in retry/timeout machinery pay for the infrastructure cost many times over, because
  the hand-rolled equivalent (`main`'s approach, scaled up) becomes an increasingly large,
  increasingly easy-to-get-wrong state machine spread across `@TransactionalEventListener`
  methods.
- **Long-running or human-in-the-loop steps** are involved (e.g. "wait up to 3 days for
  manual fraud review") — Temporal's workflows can durably sleep for arbitrary periods with
  no polling infrastructure; `main`'s approach would need a scheduler bolted on.
- **Cross-team operational visibility** matters — when more than one team owns parts of the
  saga and needs to answer "where is order X right now?" without reading another team's
  logs, Temporal's UI/CLI is a real win `main` cannot offer without building bespoke tooling.
- The organization **already operates Temporal elsewhere** — the infrastructure and
  operational cost (§3) is sunk, so the determinism/retry/visibility benefits become
  nearly free for the next saga built.

Stay with **the mainline hand-rolled orchestrator** (`main`) when:

- The saga is **small and stable** — two or three participants, one compensation path, the
  exact shape this project's saga has today. The infrastructure, operational, and cognitive
  cost of running a second server is disproportionate to what it buys for a flow this size.
- **No existing Temporal operational expertise or cluster** exists in the organization —
  standing one up just for this saga is a cost `main`'s zero-new-infrastructure approach
  avoids entirely.
- **The project's own teaching goal is DDD/hexagonal architecture and saga fundamentals**
  (per `CLAUDE.md` Section 1) rather than distributed-workflow-engine operation — `main`'s
  explicit, readable state machine is a better artifact for that purpose than a framework
  that hides sequencing behind a worker and replay mechanism.
- **Test coverage is a hard requirement before shipping** — as §3 documents, this branch
  currently trades `main`'s full saga test suite for none; a team cannot adopt this approach
  as-is without first closing that gap.

**Verdict for this codebase specifically:** the mainline choice (`main`, Section 3 of
`CLAUDE.md`) remains correct for the project's current scale and teaching goal. This branch
earns its keep as a reference for *how* a framework-based saga would look and what it would
cost — exactly the comparison-branch purpose `CLAUDE.md` Section 6 assigns it — not as a
candidate for merging.

---

## 5. How to run this branch

1. Start a local Temporal dev server: `temporal server start-dev` (matches
   `TemporalConfig.workflowServiceStubs()`'s `newLocalServiceStubs()` default endpoint).
2. Run the Spring Boot application as usual — `TemporalConfig`'s `WorkerFactory` bean starts
   the worker automatically on application startup and registers `OrderSagaWorkflowImpl`
   plus the three activity implementations against `order-saga-task-queue`.
3. Creating an order triggers `OrderSagaWorkflowStarter`, which starts a workflow execution
   visible in the Temporal Web UI (`http://localhost:8233` by default for `start-dev`).
