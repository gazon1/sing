---
title: "MVI instrumentation: one measurement, no observer API"
date: 2026-11-27
status: accepted
tags: [mvi, instrumentation, observability, diagnostics]
epic: "#3225"
issue: "#3267"
---

# MVI instrumentation: one measurement, no observer API

> Decision for issue #3267 (epic #3225). The issue's own execution plan makes this
> an outcome, not a mandate: *"If no useful generic signal remains after accounting
> for current diagnostics, close this issue as not planned rather than adding an
> abstraction for its own sake."* Step 5 was taken. One signal did remain.

## Scope — read this before citing the issue title

Issue #3267 is titled *"add a minimal instrumentation observer API"*. **This decision
does not deliver one, and that is the point of the issue, not a shortfall against it.**
What ships is a diagnostic improvement: one `warn` line on a path that previously
recorded nothing.

The distinction matters for future tracking. There is no reusable instrumentation
extension point here — nothing to observe through, no seam to extend, no contract a
feature can opt into. If a later issue needs one, it starts from the mapping below and
must bring a signal this table does not already mark as covered. A reader who finds
this ADR looking for an observer API should find that stated plainly rather than infer
it from an absence.

## Context

`MviViewModel` is shared by 36 classes; `EventBus` has exactly one production
construction site (the private bus inside the base class). The candidate signals
were already covered by Kermit, `CrashReportingPort`, the platform crash reporters
and the breadcrumb buffers. Before designing an observer the existing diagnostics
were inventoried, and each candidate lifecycle point was checked against whether
the base class can state it truthfully.

Platforms: Free Android (`FileCrashReportingPort` → rolling Kermit file log), Pro
Android (vendor-backed `TracerCrashReportingPort`), JVM Desktop
(`JvmCrashReportingPort` → no-op; the desktop Kermit file log is the only sink).
Same MVI runtime on all three.

## The mapping — reliable vs. deliberately unsupported

| Point | Reliable? | Why |
|---|---|---|
| ViewModel created / closed | **Only as a log line, not a lifecycle callback** | `init` and `onCleared` are real, ordered, and already exist. They are also *not* where a defect is; a VM that is created and closed correctly says nothing. An observer callback here would be a per-instance hook whose only consumer would be a logger that already exists. Not added. |
| Event bus closed | **Reliable** | `onCleared()` calls `close()`; the close is synchronous and ordered. Already observable through the flow completing. |
| Event discarded after close | **Reliable, already covered** | Counted in `droppedAfterClose`, logged at debug. A lifecycle race, deliberately never reported (#141). |
| Event refused by `tryEmit` on a **full** buffer | **Reliable, and was NOT covered — the gap this issue closes** | See below. |
| Event refused by `tryEmit` on a **closed** bus | **Reliable, not a separate signal** | Same cause as drop-after-close; counted by neither counter on purpose, so the two stay distinguishable. |
| `onIntent` entered | **Not a boundary.** The issue is right: this is an abstract subclass method, and a callback fires on *dispatch*, not on completion. Naming it `onIntentCompleted` would be a lie, so no callback is added. |
| Intent completed / duration / cancellation | **Not observable from the base class** | `onIntent` is synchronous and returns `Unit`; the work it starts is a `launch` on `vmScope` (via `catchTo`/`emitError`) or a fire-and-forget call. The base class has no handle to that job's completion, so it cannot report it in order. Explicitly unsupported. |
| State transition | **Not observable without lying** | `MutableStateFlow.update` may invoke its reducer more than once under CAS contention, so a "one state change" callback would fire a variable number of times for one logical update. `setState` and `updateState` are not one event either. A callback would have to sit outside the reducer to be sane, at which point it reports "a write happened", which is not a transition. Explicitly unsupported. |
| Event *delivery* / UI consumption | **Not observable from the base class** | `receiveAsFlow()` is cold and the consumer is the screen. The bus knows when a value entered the buffer, not when a collector acted on it. Accepting an event and consuming it are different facts, and the issue says not to conflate them. |
| Failures through the MVI error boundary | **Already covered** | `catchTo` reports to `CrashReportingPort` with a machine-shaped `issueKey`, and the scope's unhandled failures go through `crashReportingFailureHandler`. A second observer would duplicate the report path. |

## The one gap

`tryEmit` returns `false` when the buffer is full, and **every call site in the tree
ignores the result** (28 production calls; the two that return it,
`TaskDetailCoordinator.reportError`/`reportSaved`, are called for their effect). So
a refused event — a snackbar, an undo offer, a `NavigateBack` — was recorded nowhere.
The debug log line that exists covers only the closed-bus discard, and release
builds filter below `Warn` (`applyGlobalSeverity`), so in the build where a user
would report a missing snackbar there was no trace at all.

## Decision

**No observer API and no public counter. One `warn` log line inside `EventBus`.**

- The first refusal per bus logs **one** `warn` line — `warn` so it survives the release
  filter, once so sustained overload cannot turn every refusal into a flood. The guard is
  a private `AtomicBoolean` (`hasLoggedRefusal`, set with `compareAndSet(false, true)`),
  not a count and not state anyone reads: the number of refusals is not a fact anyone
  acts on, only whether the reader was told once.
- A closed bus is **not** logged: it is an expected teardown race, and turning it into
  a warning is how the crash-reporting channel became noise (#141).
- The message names the cause it can actually see, and the two causes differ:
  `EventBus buffer full; refused an event` for a buffered bus, and
  `EventBus refused an event; no receiver was waiting` for a
  [Channel.RENDEZVOUS] bus (capacity `0`), which has no buffer to fill. Producers
  outrunning the collector does not establish that the collector stopped, so neither
  message claims that.
- `EventBus` takes its `Logger` as a defaulted constructor parameter, the same test
  seam `crashReporter` already has, so the severity is pinned by a test rather than
  asserted in a comment.

### Two trade-offs taken deliberately

- **Suppression is permanent, not rate-limited.** A bus that refused once and later hits
  a *second*, sustained overload stays silent. A time-window limiter would report both,
  but needs a clock the bus does not have, and one line per ViewModel lifetime already
  points a reader at the code. Revisit only if a real diagnosis is missed because of it.
- **No call sites are migrated.** Checking `tryEmit`'s result at the 28 call sites might
  improve delivery guarantees, but that is a behavioural change to the screens, not a
  diagnostic one. This decision adds a report; it does not alter what any caller does.

### Why the guard is private, not exposed

Two earlier drafts of this change were shot down by review, and both corrections are
worth keeping:

1. **A public getter.** The first draft exposed `refusedWhenFull` as a public getter.
   The only production bus is `MviViewModel`'s private `_events`, and the base class
   exposes no reader, so the getter's only consumers would be tests and a
   separately-constructed bus. That is state added to justify itself — the exact thing
   this issue rejects.
2. **A counter at all.** The second draft kept it private but still as an `AtomicInt`
   compared against `1`. Nothing needs the count: this is a one-time logging guard, and
   `hasLoggedRefusal.compareAndSet(false, true)` says "the first refusal wins" as the
   whole operation, with no number to maintain and no destination for one to grow into.

What remains is a private `AtomicBoolean` whose only reader is the `if` that writes the
line.

## Rationale

- The issue asks for a signal that is not already adequately exposed. This is the
  only one that qualified: every other candidate is either already reported, or not
  truthfully observable from the base class.
- A log line in place reuses the existing diagnostics instead of adding a facade over
  them. There is no registry, no observer list, no ordering contract to document,
  nothing to compose, and no new readable surface to wire up later.
- Nothing is opt-in-expensive: an absent observer was the cheapest possible path,
  and it is the path taken.

## Consequences

- No public instrumentation extension point exists. A future need for one has to
  bring a signal this table does not already list as covered, and an ADR.
- `EventBus` now reports on a path that had no report. It still never calls
  `CrashReportingPort` for a refusal: one lost event means producers outran the
  collector, not a defect worth a crash report.
- A rejection from a live bus is detected from `ChannelResult.isClosed == false` on a
  failed `trySend`, so a closed bus is never logged as loss — pinned by a test, because
  that is the distinction the whole change rests on. Capacity `0` is detected separately
  (`capacity == Channel.RENDEZVOUS`) so the message does not claim a buffer exists.
- Callback threading, ordering and lifetime need no documentation, because there
  are no callbacks.

## Links

- Issue #3267; epic #3225; prerequisite #3265.
- `docs/decisions/2026-11-27-mvi-architecture-direction.md` — the direction this
  stays inside (no framework, no plugin layer).
- `docs/architecture/mvi-ownership.md` — the ownership inventory, updated with this
  signal.
- `2026-10-22-an-event-bus-has-one-consumer.md` — why producers outrunning the one
  collector is the condition this line reports.
