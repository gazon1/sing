# Spec — <title>

> Written by `speckit-engineering-specify`. This document states **what must be true**, never
> **how to make it true**. Choosing between a `Mutex`, an actor-like queue and a transactional lock
> is `design.md`'s decision, unless `investigation.md` already recorded a constraint that forbids
> the alternatives.
>
> Sections marked *(when applicable)* stay in the document. Mark them *Not applicable* with a
> reason — do not delete them. Cancellation, concurrency and persistence are where this codebase's
> real bugs live.

## Summary

_<Two or three sentences: the required behavior and why it matters._

## Problem

_<What is wrong or missing, in terms of behavior. From `investigation.md`, but stated as a problem
rather than as a list of findings.>

## Scope

- **In:** <what this change covers>
- **Out:** <what it explicitly does not touch>

## Non-goals

_<Things a reader might reasonably assume are included, and are not._

- <non-goal> — <why it is out>

## Current behavior

_<What the system does today. Facts only, cited to `investigation.md` or `path:line`._

## Required behavior

_<What it must do after this change. Decisions, not facts.>

## Invariants

### INV-01 — <the invariant>
<One sentence. Why must it hold, and what breaks if it does not.>

### INV-02 — <the invariant>
<...>

_<An invariant nobody could violate is not an invariant. One nobody would want to violate is not
worth having. If you cannot say what breaks, it probably is not an invariant.>

## Failure semantics

_<What happens when an operation fails: which state is left behind, whether the failure is
recoverable, what the caller observes. One row per failure `model.md` models.>

## Cancellation and lifecycle

*(when applicable)*

_<What happens when the owning scope is cancelled mid-operation. Does a state mutation roll back? Is
a partially-completed effect retried, skipped or duplicated? Who owns the scope?>

## Concurrency and ordering

*(when applicable)*

_<What happens under parallel calls. What serializes and what does not. What ordering is guaranteed
and what is merely observed today? Re-entrancy: can the same caller arrive twice?>

## Persistence and recovery

*(when applicable)*

_<What is persisted, when, and what happens on restart. Is the operation idempotent, and if not,
what makes it so? Can a replayed operation apply twice?>

## Compatibility constraints

_<What must not break: existing public APIs, persisted schemas, serialized formats, other platforms.>

## Acceptance criteria

- [ ] **AC-01:** When <precondition> and <event>, the system must <observable result>.
      `Verification:` `<TestClass>::<testFunction>` — `./gradlew :shared:jvmTest --tests '<TestClass>'`
- [ ] **AC-02:** <...>

_Rules for this list:_

- _Name a **resolvable test id**, not "add a test". The test does not exist yet — `verify-design`
  designs it and `implement` writes it. Binding to a named test is what makes the binding checkable
  rather than aspirational._
- _Assert on something **observable**: a returned value, a persisted row, an emitted event, a logged
  line. "The system must be correct" is not observable._
- _Number sequentially from `AC-01`. Never reuse a number —
  `scripts/check-req-id-uniqueness.py` exists because reused identifiers produced a traceability
  matrix that confidently reported the wrong requirement._

## Open questions

1. <question blocking a correct plan> — <who resolves it, and when>

## References

- <ADR slug, spec path, or existing test class> — <what it constrains>