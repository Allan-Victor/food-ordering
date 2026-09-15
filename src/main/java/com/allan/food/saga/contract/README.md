# Saga Participants: Compensatable vs. Pivot

It is worth pausing here because the differences between the two participants are ultimately consequences of **one classification: whether the saga step is compensatable or a pivot**.

## Both Participants Side by Side

| | **Payment — Compensatable** | **Restaurant — Pivot** |
|---|---|---|
| **Inbound methods** | 2 — `do` and `undo` | 1 — `do`; no undo exists |
| **Aggregate mutability** | Mutable — `markRefunded()` | Immutable after creation |
| **Terminal states** | 3, with one reachable through reversal | 2, both permanent |
| **Reply on anomaly** | Always, to unblock `CANCELLING` | Always, to unblock the saga |
| **Failure mode if wrong** | Recoverable — money can move back | Permanent — food is already cooking |

## The Key Lesson

If you take **one thing** into your next saga, make it this:

> **The shape of a saga participant should be derivable from the classification of its step. If it isn't, one of the two is wrong.**

In other words:

- A **compensatable participant that cannot undo** its effect is misclassified.
- A **pivot participant that exposes a revoke or undo operation** is not really a pivot.
- The participant's API, aggregate mutability, state transitions, and failure handling should all follow naturally from the step's classification.

### The Rule

**Step classification → Participant shape**

```text
Compensatable step
        ↓
Do + Undo
        ↓
State may be reversed
        ↓
Mutable aggregate
        ↓
Recoverable failure


Pivot step
        ↓
Do only
        ↓
No undo
        ↓
Permanent state transition
        ↓
Failure requires saga-level handling