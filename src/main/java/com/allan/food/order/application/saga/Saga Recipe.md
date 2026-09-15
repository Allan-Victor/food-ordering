# Architectural Reference: Distributed Saga Implementation Pattern

## Core Philosophy: "Strict Aggregates, Tolerant Sagas"

In distributed systems and microservices architectures, data consistency across boundaries must respect the fundamental design tenet:

> **The aggregate is strict; the saga is tolerant.**

Do not compromise domain invariants or loosen business rules to accommodate unreliable messaging transports, network partitions, or delayed deliveries. The domain model remains strict and uncompromising within its transactional boundary, while the saga orchestrator/choreographer absorbs transport noise, out-of-order deliveries, and network retries at the boundary.

---

## The 6-Step Saga Engineering Recipe

```
+---------------------------------------------------------------------------------------+
|                                    SAGA LIFECYCLE                                     |
|                                                                                       |
|   [Compensatable Steps]  ----->  [Pivot Step]  ----->  [Retriable / Forward Steps]    |
|   (Reversible via undo)         (Point of No Return)   (Must succeed eventually)      |
+---------------------------------------------------------------------------------------+
```

### Step 1: Model the Explicit State Machine

-   **Pre-code Specification**: Model states, transitions, terminal states (`Completed`, `Compensated`, `Failed`), and compensation triggers before implementing handlers.
-   **Scope Boundary Rule**: A saga state machine must fit on a single page or conceptual diagram. If complexity exceeds this boundary, decompose the workflow into two or more discrete, coordinated sagas.

### Step 2: Classify Step Types by Physical Irreversibility

Categorize every step in the sequence into one of three classifications based on physical reality (external side-effects), not technical abstractions:

1.  **Compensatable Transactions**: Steps that can be semantically reversed if subsequent failures occur (e.g., releasing a credit hold, canceling an unfulfilled booking).
2.  **Pivot Step**: The definitive transaction that produces an irreversible side-effect (e.g., physical payment capture, charging an external card, dispatching physical goods).
    -   Everything *before* the pivot must be compensatable.
    -   Once the pivot commits, compensation is no longer possible.
3.  **Retriable Transactions**: All steps *after* the pivot step. Because rollback is no longer an option, these steps must be designed to retry indefinitely until eventual success (e.g., sending receipt emails, notifying downstream read models).

Step Category

Reversible?

Position in Flow

Handling on Failure

`Compensatable`

Yes

Pre-Pivot

Trigger compensation backwards

`Pivot`

No

Boundary

Point of no return

`Retriable`

N/A

Post-Pivot

Retry with backoff until completed

### Step 3: Align with Existing Aggregate Lifecycle States

-   **Single Source of Truth**: Leverage the aggregate's existing lifecycle `enum` / state field rather than creating a disconnected, parallel saga state attribute.
-   **Invariant Protection**: Parallel state trackers create split-brain states and unenforced business invariants.

### Step 4: Define Immutable Contracts in a Shared Module

-   **Contract Hygiene**: Define commands and replies using immutable structures (`records` / DTOs) composed exclusively of primitive types and standard identifiers—never expose rich domain model entities across module or service boundaries.
-   **Mandatory Metadata Headers**: Every message payload must contain:
    -   `correlationId`: Identifier tracking the saga instance across all services.
    -   `traceId`: Distributed tracing span identifier for APM observability.
    -   `timestamp`: Explicit UTC event creation timestamp (`Instant`).

```json
{
  "correlationId": "ord-883b-41fa",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "timestamp": "2026-08-27T11:08:00Z",
  "payload": {
    "orderId": "ORD-10928",
    "amount": 42.50
  }
}
```

### Step 5: Enforce Transaction Boundaries & Idempotency Gates

-   **Granular Transactions**: Exactly one database transaction per saga step. Avoid distributed 2PC (`Two-Phase Commit`) or long-running database locks.
-   **Unified Idempotency Gate**: Every message consumer must evaluate incoming events through a shared gate that classifies delivery into three scenarios:
    1.  `Expected`: Message arrived in order for the first time -> Process and transition state.
    2.  `Duplicate`: Message already successfully processed -> Acknowledge and absorb silently without re-executing business logic or throwing errors.
    3.  `Anomalous`: Stale, out-of-order, or obsolete message -> Log diagnostic metadata and discard/absorb gracefully.
-   **Concurrency Defense**: Pair the idempotency gate with optimistic concurrency control (`@Version` attribute on the aggregate). The version check detects concurrent writes, and the idempotency gate cleanly absorbs the resulting retries.

### Step 6: Bind Contextual Observability at Entry Points

-   **MDC Context Propagation**: Bind `correlationId` and `traceId` to the logging context (`MDC` / thread-local / asynchronous context) at every message consumer and API entry point.
-   **Deterministic Debuggability**: Enables single-query log aggregation across all distributed handlers during production incidents.

---

## Architecture Verification Checklist

-    **State Machine**: Are all states, failure paths, and terminal outcomes documented?
-    **Pivot Identified**: Is there a single, unambiguous pivot transaction identified?
-    **State Parity**: Is the saga state driven directly by the domain aggregate root?
-    **Contract Decoupling**: Are messages free of domain types and equipped with `correlationId`, `traceId`, and `timestamp`?
-    **Idempotent Boundaries**: Does every step execute in an isolated transaction backed by optimistic locking and duplicate absorption?
-    **Context Tracing**: Are logs enriched with correlation metadata from the first line of handler execution?