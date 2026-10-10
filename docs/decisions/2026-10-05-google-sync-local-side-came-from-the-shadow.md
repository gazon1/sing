---
title: The push direction was dead: the merge's local side came from the ancestor
date: 2026-10-05
status: accepted
slug: google-sync-local-side-came-from-the-shadow
---

# The push direction was dead: the merge's local side came from the ancestor

## Context

`GoogleSyncEngine` shipped with a 3-way merge, a shadow per event, and seven passing tests.
It could pull Google changes into tasks. It could not write anything to Google at all.

The cause was one expression:

```kotlin
val merge = BidirectionalMerge.merge(
    ours = event.withFieldsFrom(base),   // ← remote event, with every field replaced by base
    theirs = event,
    base = base,
)
```

`withFieldsFrom(base)` returns the remote event with each app-owned field set to the shadow's
value. So `ours` was, by construction, field-for-field equal to `base`. In
`BidirectionalMerge.decide`, `localChanged = baseKnown && ours != base` is therefore
identically `false`, so `FieldOutcome.Push` could never be constructed, so `merge.pushes` was
always empty, so `eventSource.patch(...)` was unreachable.

`eventSource.insert(...)` was not called from anywhere at all.

The net effect: renaming a task, moving its due date, or creating a task in the app did
nothing to the calendar. The feature was called two-way and was one-way.

## Why no test caught it

`GoogleSyncEngineTest` had two assertions touching the patch list, and both asserted it was
**empty** — `assertTrue(source.patched.isEmpty(), "adopting must not also write")` and the
same for a cancelled event. Both are correct and both pass under a push implementation that
works. There was no test in which a local task moved and a patch was expected, so the
missing capability was indistinguishable from the intended one.

The deeper cause is structural, not clerical: the engine had **no access to local tasks**. Its
own KDoc said it *"does not touch tasks"*, and by that rule it had nothing local to put in
`ours`. The first cut satisfied the rule by borrowing the ancestor's values, which is the one
thing an ancestor must never be used for.

## Decision

### The local side comes from the task, via the applier

The engine asks `applier.desiredEventFor(taskId, remote)`, which reads the task and applies
its five owned fields over the remote event. The task layer stays behind the applier, so the
engine still does not touch tasks — it just stops pretending to know the local state.

`withFieldsFrom(base)` survives only as the **fallback** for a shadow with no `task_id`, and
its KDoc now says why it must never be the general case: "we have no opinion" is a defensible
answer when there is genuinely no task, and a silent way to disable pushing when there is.

### The patch payload is `remote` with the task's fields applied

The inverse of the pull direction. Google's `patch` overwrites what it sends and leaves the
rest, so carrying `remote` as the base keeps `location` and `recurrenceRule` intact. Sending a
task-derived event wholesale would blank a real location and rewrite a series, because the app
has no model for the first and deliberately does not parse the second.

### Insert is a separate walk, because the pull walk cannot see it

A pull walk iterates events that are *already in Google*. A task with no Google event appears
in no page, so no amount of pull can discover it. `GooglePushPlanner` is a pure function of
`(desired, shadows)` that emits `Insert` for tasks with no shadow and `Patch` for tasks that
disagree with one, and the engine executes only the inserts — patches are already settled
during the pull walk against a live `etag`, and re-issuing them would race that merge.

The planner is pure specifically so both cases are testable with no task table, no HTTP
client and no database.

### No deletes

Deliberately asymmetric. A pull walk that decides "this event is gone" from an empty or
truncated page would delete real entries from a user's calendar, and being wrong there is far
more expensive than being late.

## Rationale

The lesson generalises past this feature: **an ancestor in the "ours" position is always a
bug**, because an ancestor is the one value that cannot differ from itself. If a merge cannot
express the local side, the merge has no local side.

It also generalises about tests: an assertion that a write *did not* happen is worth nothing
as evidence that the write path works. `assertTrue(patched.isEmpty())` passed identically
against a correct engine and a broken one.

## Consequences

- `GoogleSyncEnginePushTest` exists and asserts a rename reaches Google, that the pushed patch
  keeps Google's location, and that a completed or trashed task is not created. It is written
  against a real `GoogleTaskApplier` and `FakeTaskRepository` rather than a mock, because the
  bug lived exactly at that seam — a mocked applier would have answered the question it was
  asked rather than the one the engine was really asking.
- `desiredEventFor` reads one task per event per pass. That is N reads on a Room-backed table
  on the pull walk. Acceptable at calendar scale (hundreds), and worth revisiting only if the
  calendar holds thousands of live events.
- The push walk calls `observeAll().first()` once per pass, mirroring `CalendarSyncWorker.kt:71`.
  The two paths must agree on which tasks belong on a calendar; if they ever diverge, a task
  would appear on one calendar and not the other, so the selection rule is duplicated
  deliberately and cross-referenced in both KDocs.
- `desiredEvents` produces a placeholder event id. Tasks are matched to events by `task_id`,
  never by event id, and the planner substitutes the stored id. The placeholder is a named
  constant so it does not read as meaningful.

## Links

- `sync/GoogleSyncEngine.kt` — `applyEvent`, `pushLocalTasks`, `desiredOverRemote`
- `sync/GoogleTaskApplier.kt` — `desiredEventFor`, `desiredEvents`
- `domain/logic/GooglePushPlanner.kt`
- `sync/GoogleSyncEnginePushTest.kt`, `domain/logic/GooglePushPlannerTest.kt`
- `2026-10-05-google-sync-decides-and-the-applier-writes.md`
- `2026-10-05-google-calendar-two-ports-and-local-wins.md`
