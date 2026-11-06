---
id: 2026-10-19-greeting-observed-not-snapshotted
title: The agenda's greeting is an observed input, not a value snapshotted into the state
description: Why AgendaViewModel keeps the greeting in a StateFlow and combines it into the Loaded state, instead of reading it while building the list
status: accepted
deciders:
  - agent
date: 2026-10-19
---

## Context

`AgendaViewModel` shows a time-of-day greeting as the second line of the agenda's bar
title, and the thresholds that decide it ("good morning ends at 12") live in
`GreetingSettingsRepository`, a settings screen away.

The greeting used to be computed once, in `init`, and never again — a user who moved the
end of the morning went back to an agenda that still said it. Making it *observable* is
the fix, and the interesting question is how the observed value reaches
`AgendaUiState.Loaded.greeting`.

The first attempt observed the setting in one coroutine and, when it changed, wrote the
new greeting into an already-published `Loaded` with `copy(greeting = …)`. The list
builder read the same value into the `Loaded` it constructed:

```kotlin
// list builder
AgendaUiState.Loaded(sections = …, greeting = _greeting.value)
// …then, in the collector, through the CAS:
updateState { current -> fresh.copy(isSelectionMode = …, selectedTaskIds = …) }
```

That is two writers for one field, and the second one publishes a value *snapshotted
earlier*: if the settings emission lands between the map and the `updateState`, the newer
greeting is overwritten by the older one. The settings flow does not re-emit a threshold
the user has already changed, so nothing corrects it. The screen keeps the stale greeting
until it is left and re-entered — the bug the change was meant to fix, reachable
whenever two processes share the database (the desktop app and `:mcp-server` do).

Reading the greeting inside the `updateState` instead narrows the window but does not
close it: the read and the CAS are still two steps, and the seeding case — no `Loaded`
yet, so the greeting has to come from somewhere else — has a window of its own. The
value can end up empty for the rest of the ViewModel's life if the one settings emission
is consumed against a `Loading` state.

## Decision

The greeting has exactly one writer: a `MutableStateFlow<String>`
(`AgendaViewModel._greeting`), kept in step with the hours by a collector.

The `Loaded` state is built by `combine(listFlow, _greeting)`:

- `_greeting` is an upstream of the state pipeline, not a value copied into it, so the
  greeting in a published `Loaded` is never a snapshot of an older value — a settings
  change *is* a new emission, and the state converges on the newest greeting whatever the
  interleaving with a task emission.
- `combine` re-emits when either side changes, reusing the other side's last value. A
  greeting change therefore republishes the **same** `sections` instance (the agenda is
  not re-evaluated, the selection is not rebuilt), and a task change republishes with
  whatever greeting `_greeting` holds at that moment.
- A `StateFlow` upstream never completes, so a settings flow that fails and ends cannot
  end the agenda's task collection. Combining the settings flow *directly* (rather than
  through `_greeting`) would do exactly that: `combine` completes when any upstream does.

The initial value is empty, not a guess. An empty greeting means "no greeting to draw
yet" — the screen (`AgendaContent`) skips the second line — and it is also the documented
degradation when the hours cannot be computed. A "Good evening" placeholder is the most
likely outcome, but it is still a greeting nobody configured, and it is what remains on
screen if the read fails.

## Consequences

- The greeting is correct on a live screen without leaving and re-entering it.
- The task list is not rebuilt by a greeting change; the selection and the open
  blocked-close question survive it.
- A greeting that cannot be computed is empty rather than wrong.
- The bar's second line appears one settings read after the list does, instead of
  showing a placeholder that may be wrong. Empty is a visible, honest state; the
  alternative is a wrong greeting that is invisible as a bug.
- A failing settings flow degrades the greeting for the ViewModel's lifetime (`catch` is
  terminal). In production the preference wrappers already turn a read error into the
  default value (`Flow<Preferences>.catchDataStoreIoError`), so what reaches the `catch`
  is a defect, and resubscribing to a broken store would only spin.
- The greeting is recomputed when the *hours* change, not when the clock crosses an hour
  boundary. A screen left open across noon keeps the greeting it had. That is unchanged
  from the original behaviour and out of scope here.

## Supersedes

- `2026-10-18-greeting-init-no-runblocking.md` — how to compute a *once-only* greeting
  without `runBlocking`. The once-only computation no longer exists.
- `2026-10-18-greeting-computation-in-viewmodel-collector.md` — the same one-shot
  computation, placed in the collector instead of `init`.

## Alternatives considered

- **Snapshot at map time and copy into the state on change** — the two-writer design
  above; it loses the newer greeting under realistic interleaving.
- **Re-read the greeting inside the `updateState`** — narrows the window to a
  read-then-CAS; still two writers, and the first-`Loaded` seeding case stays open.
- **Combine the settings flow directly into the state pipeline** — removes the two-writer
  problem too, but couples the agenda's first emission (and its lifetime) to the settings
  read: a settings flow that never emits, or one that fails and ends, stalls or ends the
  task collection. `_greeting` keeps the decoupling.
- **`runBlocking` in `init` to compute the first greeting eagerly** — forbidden by
  `NoRunBlockingRule`, and it would still need observing.
