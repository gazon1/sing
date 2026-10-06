---
date: 2026-10-06
slug: google-sync-failures-were-shaped-like-skips
status: accepted
---

# A Google pass that failed was shaped exactly like a pass that never ran

Three defects found reviewing the merged Google sync, all the same shape: **the code did
something, and reported something else.** None threw. None crashed. Each rendered to the user
as "nothing happened", which for a sync means "your calendar quietly stopped updating".

## 1. `Outcome.ran = false` for a pass that ran and threw

`GoogleSyncCoordinator` reported every non-success as one shape:

```kotlin
data class Outcome(val ran: Boolean, val result: PassResult? = null, val skippedBecause: String? = null)
```

A failure went through the same constructor as declining to act, so it set `ran = false`.
`GoogleSyncWorker` read that:

```kotlin
return if (outcome.ran) Result.success() else { log.w { "did not run: …" }; Result.success() }
```

So a Google outage produced: a log line saying the pass *did not run*, and `Result.success()`
back to WorkManager. The worker did not retry, the scheduler did not back off, and nothing was
shown to the user. One transient 503 would have stopped background sync until the app was
killed and relaunched — and even then, only if the next attempt happened to succeed.

**Fixed** by making the outcome a three-case sealed hierarchy — `Completed`, `Declined`,
`Failed` — so "we chose not to act" and "we tried and it broke" are different types. The
worker now returns `Result.retry()` for `Failed`. A caller cannot mistake one for the other,
because it will not compile if it tries.

## 2. The Google "Sync Now" button drove the *system* calendar

`GoogleSyncSection` dispatched `CalendarSyncIntent.SyncNow`, which reaches
`CalendarSyncOrchestrator.requestSync(...)` — the one-way projection onto a **device**
calendar. The Google half is a two-way peer sync with its own coordinator, its own cursor, and
its own credentials. Pressing Sync Now under Google started a system-calendar pass and reported
*that* pass's result.

This is the most deceptive of the three, because it produced plausible output. The button
looked alive, the status line changed, and neither fact had anything to do with Google.

**Fixed** with a distinct `CalendarSyncIntent.SyncGoogleNow` that reaches `GoogleSyncCoordinator`.

## 3. A failed pass wrote nothing the user could see

`googleError` covered only `listCalendars()`. Once a calendar was selected, a pass could fail
with no trace on screen. The user saw an unchanged screen, which is indistinguishable from an
app that has stopped syncing.

**Fixed** with `googleSyncing` / `googleSyncError` / `googleLastSyncedAt` on the UI state, all
fed by the coordinator's own outcome, and rendered in the Google half. The success clock
advances only on `Completed` — reporting "last synced" after a failure is the confidently-wrong
answer this feature keeps having to avoid.

## Why these three are one decision, not three fixes

They share a single root: **an outcome was modelled as a boolean, where the domain has three
states.** `ran` was the boolean, and it could not distinguish "not configured" from "broken".
Every one of these bugs is what a lossy boolean looks like once it meets a user.

The generalisable rule:

> When a value distinguishes *did not happen*, *refused to happen*, and *tried and failed*,
> model it as three cases. A boolean that collapses them will, at some point, collapse the
> failure into the skip — and the skip is the one nobody retries.

This is the same lesson as `MergeResult`'s named accessors, one layer up. There, a `List`
index lost a field name. Here, a `Boolean` lost a state name. Both were fixed the same way:
name every case at the type that owns them, so the next caller cannot lose one.

## What made this findable, and what was not

**The seam paid for itself immediately.** `GoogleSyncCoordinator` could not be tested because
it took a concrete `GoogleSyncEngine` needing four DAOs. Extracting `GoogleSyncPass` — five
lines — made the anonymous guard testable *and* gave the failure path a place to be asserted.
Both fixes above would have been caught by the test that now exists.

**The gap that remains:** `find-unwired-surfaces.py` looks for declarations with no call site.
It cannot see a button that calls *something* which is merely the *wrong thing* — defect 2 was
a live, reachable, correctly-wired call to the wrong target. The script's question is "is this
used?", and the defect was "is this used *correctly*?".

Closing that needs intent-level assertions, not reference counting. Recorded in the gaps ADR
rather than attempted here: a prototype for the related "read but never written" check produced
63 candidates of which the first was a false positive, which says more about the difficulty of
the heuristic than about the code.

## A fourth bug, in the test helper that was supposed to catch it

Writing the tests above surfaced a trap in `awaitState`, the helper this repo tells every
agent to use. It waits by advancing virtual time:

```kotlin
while (!predicate()) { if (currentTime > deadline) fail(...); advanceUntilIdle() }
```

When the awaited state is **unreachable**, there is nothing left to schedule, so
`advanceUntilIdle()` returns instantly and virtual time never moves. The deadline is
therefore unreachable *by construction* — the loop spins at 100% CPU until the suite is
killed.

That is exactly what happened. A test of mine set up "no calendar selected" and then awaited
`googleReady`, which is *defined* as having a calendar. It spun for over half an hour across
two runs, and for most of that time I read it as CPU starvation from a competing worktree's
build — the machine genuinely was loaded, so the misdiagnosis was easy to believe. A `jstack`
of the test worker was what actually identified it.

Fixed by adding a wall-clock backstop to `awaitState`: a predicate that never holds, with
nothing scheduled, now fails in bounded real time with a message naming the unreachable state.
The virtual-time deadline is untouched, so genuinely slow tests are unaffected.

**The generalisable rule:** virtual time is a fiction that only advances when work is
scheduled. A timeout expressed purely in virtual time is not a timeout when nothing is
scheduled — it is an infinite loop that looks like patience. Any wait-for-state helper needs a
real-time bound as well, and a test asserting the wait is unreachable is only trustworthy if
something bounds it.

## Links

- `sync/GoogleSyncCoordinator.kt` — the three-case `Outcome`
- `sync/GoogleSyncPass.kt` — the seam
- `presentation/CalendarSyncViewModel.kt` — `SyncGoogleNow`, the three status fields
- `work/GoogleSyncWorker.kt` — `Failed` now retries
- `test/helpers/AwaitState.kt` — the spin guard, and `AwaitStateTest` proving it fires
- `2026-10-05-google-sync-known-gaps-and-field-drift.md` — items 1 and 3, now closed