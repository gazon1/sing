---
name: speckit-engineering-specify
description: 'Turn an investigation into spec.md — required behavior, INV-NN invariants and AC-NN acceptance criteria each bound to a resolvable test id. Second step of Engineering SDD; states semantics, never implementation.'
disable-model-invocation: true
---

# Engineering SDD — Specify

## User Input

```text
$ARGUMENTS
```

`$ARGUMENTS` carries `slug`, `depth` and `request`.

## Target

**Read first, every time:** `.specify/templates/engineering/conventions.md`.

- Read: `openspec/changes/<slug>/investigation.md`, and the code it cites.
- Write: `openspec/changes/<slug>/spec.md`
- May revise: `openspec/changes/<slug>/.openspec.yaml` (`skip_specs` only)

## Goal

Turn findings into a **contract**. State what must be true. Do not state how.

### 1. Decide whether a capability spec is required

Most engineering work changes no observable capability contract, so `.openspec.yaml` keeps
`skip_specs: true`.

If the change **does** alter behavior a capability spec describes — a user-visible or otherwise
externally observable contract, not an internal detail — then:

1. delete `skip_specs: true` from `.openspec.yaml`,
2. author `openspec/changes/<slug>/specs/<capability>/spec.md` in OpenSpec delta language
   (`## ADDED Requirements`, `### Requirement: REQ-<CAP>-NNN`, and `#### Scenario:` blocks with
   Given/When/Then),
3. record why in `spec.md` under `## Scope`.

The marker and a `specs/` directory are mutually exclusive. Check `openspec list --specs` first —
if a capability is already covered, you are modifying it, not creating one.

### 2. Write `spec.md`

Required sections, in order. Sections marked *(when applicable)* stay in the document and are marked
*Not applicable* with a reason — never deleted.

```
# <title>

## Summary
## Problem
## Scope
## Non-goals
## Current behavior
## Required behavior
## Invariants
## Failure semantics
## Cancellation and lifecycle   (when applicable)
## Concurrency and ordering     (when applicable)
## Persistence and recovery    (when applicable)
## Compatibility constraints
## Acceptance criteria
## Open questions
## References
```

- **Current behavior** vs **Required behavior** — two separate sections, never merged. If the
  current behavior came from `investigation.md`, it is a fact; the required behavior is a decision.
- **Invariants** — `INV-NN` each with *why it must hold*. An invariant nobody could violate is not an
  invariant; one nobody would want to violate is not worth having.
- **Sections marked when applicable** — cancellation, concurrency and persistence are the three most
  often silently skipped. Every one of them is a place a real bug has lived in this codebase. Answer
  them or justify why they do not apply.

### 3. Write acceptance criteria

This is the load-bearing part of the whole pipeline. Every criterion binds to a test that can be run
and can fail.

```
- [ ] AC-01: When <precondition> and <event>, the system must <observable result>.
  Verification: `<TestClass>::<testFunction>` — `./gradlew :shared:jvmTest --tests '<TestClass>'`
```

Rules:

- **Name a resolvable test id** (`Class::function`) plus the command that runs it. "Add a test" and
  "should work" are not criteria. An `AC-NN` whose `Verification:` line names no test is an
  unfinished criterion — mark it, do not let it pass silently.
- The test does not exist yet. That is expected: `verify-design` designs it and `implement` writes
  it. Binding to a **named** test is what makes the binding checkable later instead of aspirational.
- Prefer assertions on **observable** results — returned value, persisted row, emitted event, logged
  line, test-visible trace. "The system must be correct" is not observable.
- Number sequentially from `AC-01`. Never reuse a number. The gate that used to check this
  was removed on 2026-10-19, so the rule is now only as good as the reviewer: reused
  identifiers produced a traceability matrix that confidently reported the wrong requirement.

## Forbidden

- Choosing the implementation. "Use a `Mutex`" belongs in `design.md`. `spec.md` says operations must
  be serialized; whether that is a mutex, a queue or a transaction is a plan decision — unless an
  existing constraint in `investigation.md` already forbids the alternatives.
- Writing user stories. Internal work has no persona and no user-visible outcome. Write the
  contract, not a narrative about someone wanting a feature.
- Restating the ADR corpus. Link to an ADR by slug and add only what is new.
- Promoting a hypothesis from `investigation.md` into `## Required behavior`. An unconfirmed cause
  is a hypothesis; if you need one, keep it in `investigation.md` and say in `## Open questions`
  that the plan depends on resolving it.
- Writing a criterion you have no way to observe.

## Done When

- `spec.md` exists with every required section, including the ones marked *when applicable*.
- Every `AC-NN` has an `INV-NN`-backed rationale and a `Verification:` line naming a test id.
- `.openspec.yaml` either still has `skip_specs: true` with a stated reason, or the capability delta
  exists and the marker is gone.
- No file outside `openspec/changes/<slug>/` has been modified.