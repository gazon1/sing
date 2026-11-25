---
title: "MVI state is observed read-only"
date: 2026-11-26
status: accepted
tags: [mvi, viewmodel, state, contract]
---

# MVI state is observed read-only

## Context

`MviViewModel` had two public names for the same thing and one of them was
mutable:

```kotlin
protected val _state: MutableStateFlow<S> = MutableStateFlow(initialState)
public val state: MutableStateFlow<S> get() = _state
```

`state` handed every caller — screens, tests, anything with a reference to the
ViewModel — a writable handle on the state holder. A caller could write state
without the ViewModel deciding anything, and the event bus would then stop
matching what the screen showed: the state the screen renders and the state the
ViewModel believes it is in are two different things.

A repository-wide census for issue #3226 measured what the mutable handle was
actually used for:

| Use | Call sites | Verdict |
|---|---|---|
| `state.value` read | ~185, all in tests | real, and unaffected |
| `state.value = …` | 0 | — |
| `state.update { … }` | 0 | — |
| `state.emit(…)` | 0 | — |
| `state.compareAndSet(…)` | 0 | — |
| `_state.value` read in subclasses | 8 files | read-only |

So the mutation path was public and unused, and the reads were everywhere.

The base class also had two names for the write side — `updateState { }` and
`setState(…)` — which the ADR `2026-09-27-mvi-single-state-entry-and-vm-sweep.md`
had already reduced to two members of one entry point. What was left open was
the other half of the contract: what a caller is allowed to *do* with the state
it observes.

## Idea

Make the observation side read-only the way the update side already was, and
keep the names that callers use.

## Decision

**The state a caller observes is a `StateFlow`, and the backing holder is
private.**

```kotlin
private val _state: MutableStateFlow<S> = MutableStateFlow(initialState)

val stateFlow: StateFlow<S> = _state.asStateFlow()

val state: StateFlow<S> get() = stateFlow

protected val currentState: S get() = _state.value
```

- **`stateFlow` is the canonical observation property.** It is the one a
  screen collects and the one the test harness reads
  (`stateAccessor = { vm.stateFlow }`).
- **`state` stays, as a read-only alias.** Removing it would break ~185 test
  call sites for no behavioural gain; the change the issue asks for is the type
  of the handle, not its removal. The alias is what makes the migration
  source-compatible.
- **`_state` is private, not `protected`.** A protected backing flow is a
  second write path for subclasses — the exact bypass this change removes.
- **`currentState` is the subclass read.** The eight subclasses that read
  `_state.value` now read `currentState`: the same value, synchronously,
  without a new flow or a new coroutine.
- **Writes stay in `updateState` / `setState`.** No new writer is added.

## Rationale

The type of the handle is the whole leak. A `MutableStateFlow` in a public
property is a promise that anyone may write; `asStateFlow()` keeps the same
value, the same equality conflation and the same emission timing, and removes
the promise.

`state` was kept because the census said the reads are real and the writes are
not: the cost of removal was 185 broken call sites, the benefit was a shorter
name. An alias costs one line and keeps every reader working.

`_state` was made private because "read-only" that stops at the class boundary
is not read-only: a subclass that can reach a `MutableStateFlow` can write
state outside the reducer, and the invariant this change establishes would be
one refactor away from being false. `currentState` gives the subclass the
snapshot it actually needed — the eight call sites were all reads — and
nothing else.

## Consequences

- **The public type changed** from `MutableStateFlow<S>` to `StateFlow<S>`.
  Source-compatible for readers, not for writers: there were no writers.
  Binary compatibility is not preserved for code compiled against the old
  type — the repository's targets are Android and JVM Desktop, and both are
  rebuilt from source.
- **Two names for one observation API.** `state` and `stateFlow` are the same
  flow. `stateFlow` is canonical; `state` is the short name. The cost is that
  a future developer may treat them as two sources of state — they are not,
  and both delegate to the same `_state`.
- **Subclasses read through `currentState`.** A snapshot read is fine inside a
  reducer or an intent handler; logic that leans on synchronous snapshots
  instead of on the state flow is a smell worth watching.
- **The regression test is in `commonTest`.** It asserts that `state` is not a
  `MutableStateFlow` and that it mirrors `stateFlow` — the invariant is about
  the base class's contract, so it needs no platform.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/MviViewModel.kt`
  — the contract.
- `shared/src/commonTest/kotlin/com/singularity/todo/core/ui/mvi/MviViewModelTest.kt`
  — the regression test.
- `.agents/skills/singularity-todo-mvi-framework/SKILL.md`
  — how the framework is described to agents.
- `.agents/skills/singularity-todo-feature-scaffold/SKILL.md`
  — the scaffold checklist that mentions the public state type.
- `docs/decisions/2026-09-27-mvi-single-state-entry-and-vm-sweep.md`
  — the write side of the same contract.
- gazon1/sing#3226 — the issue this resolves.
- gazon1/sing#3225 — the epic; its own direction ADR (#3235) records where
  the framework as a whole goes, and this ADR records one contract inside it.
