---
title: "Desktop Flow Tests Share One Jvm And One Fails Only In The Batch"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED — re-measured 2026-10-07; the bundle narrows it to a state, not a write**

**Tracked as:** #462

**Re-measurement (2026-10-07).** The failure bundle settles the first question — is the
value saved? — and it is:

```
+0.98s  awaitTag(priority_option_high)    OK    0.65s      <- the click landed
<then the label assertion fails>
```

`db-state.txt` for the same attempt:

```
TaskEntity(id=robot-task-0, title=Buy milk, …, priority=High, …,
           updatedAt=1789552800000, sync=SyncColumns(…))
```

So the write completed, `updatedAt` moved, and only the *rendered label* stayed at
"No priority". That rules out the double-fire and the lost-click hypotheses outright, and
narrows the defect to: the slot's `mutate()` writes through `core.updateTask { … }` and
never publishes the result back to the slot, so `TaskEntitySlot` keeps serving the task it
loaded until some *other* flow re-emits it.

The subscription exists — `TaskDetailCoordinator` collects
`core.taskRepo.observe(taskId)` and sets `taskLoad` — which is why this is a **race** rather
than a dead path, and why it passes in isolation: the emission arrives, the batch just
asserts before it does. `waitUntil` pumps the Compose clock, so a genuine 5-second absence
is a genuine absence; what varies between runs is which flow got there first.

**What is ruled out, measured rather than argued.** Not the click (the bundle records it
succeeding). Not the write (`priority=High` with a moved `updatedAt`). Not a lost
`@Tag` — `assertTagDisplayed` and `assertTextDisplayed` fail differently and this one
fails on *text*. Not host contention: a run under load 40 reproduced it and a run at load
16 did not, which is the definition of ordering-dependent rather than resource-dependent.

**Try next, in this order.**

1. **Publish from `mutate()` rather than waiting for the repository flow.** `mutate` is
   `core.updateTask(id) { … }.onFailure { … }`; the fix is to apply the same transform to
   the slot's own state on success. That removes the race for *every* slot that uses
   `mutate` — priority, due date, estimate, recurrence — rather than for one symptom.
   The trade-off is real and should be written down: the repository flow remains the
   authority, so this must be a cache update, not a second source of truth.
2. **Assert through the state, not the render.** If the race is inherent, the test should
   await the label with a bounded poll instead of asserting once. Cheaper, and it hides a
   real user-visible lag, so it is second and not first.
3. **Find what makes the batch late.** 182 active coroutines at failure, including leaked
   `CurrentUser` collectors, points at a scope outliving its class; that is a leak worth
   fixing on its own merits but it is not this test's cause.

**Found in:** MR-5 final `./check.sh` — the only observation in five runs.

**Original observation (2026-07):** `ProjectsFlowTest` failed once with
`NullPointerException` from `ProjectDetailViewModel.getDraftState()`. Not reproducible in
three `--rerun-tasks` runs. Filed as a one-time flake, and was.

**Re-measured 2026-10-07.** `:desktopApp:test` had 8 failures across 6 classes. Because
`desktopApp/build.gradle.kts` sets `parallel.mode.classes.default = same_thread`, the
classes run *sequentially in one JVM* — so a first guess of "load" was wrong and a second
guess of "state leaking between classes" was wrong too: dumping the result files in
completion order showed the failures interleaved with passes, which rules out anything
monotonic. The harness's own failure bundle (`desktopApp/build/diagnostics/<Class>/`) is
what actually placed it, and six Gradle runs had not.

**Seven of the eight were one binding, now fixed.** `testPlatformModule()` binds
`single<UnitOfWork> { RoomUnitOfWork(get()) }` against `FakeAppDatabase`. That fake
*extends the generated `AppDatabase`*, so it is a `RoomDatabase` by type, but Room never
opened it and Room's `coroutineScope` is a `lateinit` only Room's own initialisation
assigns. So the first write of any test that saved a task threw
`UninitializedPropertyAccessException: lateinit property coroutineScope`, surfaced on screen
as a generic "Save failed", left the editor open, and wrote nothing — which reads exactly
like a timeout, and is why it was misdiagnosed twice. `FakeUnitOfWork` is the pass-through
the comment there always described; it is now bound instead.

**What remains is one test.** `SetPriorityFlowTest > choosing_high_updates_the_row_label`
fails in the full suite and **passes in isolation**. Unlike the others it is not a broken
double: the DB snapshot shows `priority=High`, so the value persisted and only the row
label is stale. So the class of defect here is narrower and different — a UI that did not
re-render for a state change that demonstrably happened.

Two things worth keeping in mind when reading any other failure in this suite:

- `coroutines.txt` in the bundle reported 182 active coroutines at failure, including
  leaked `CurrentUser` collectors. A leaked scope outlives the class that made it, which
  is the most likely home for a batch-only failure.
- `DraftMviViewModel.save()` had three failure arms and only the `throw` arm logged. A
  draft rejected by validation, or refused by the use case, left no trace in the bundle —
  and `CreateTaskFromDraft` dropped the `cause` when wrapping into `AppError.Persistence`,
  so the stack died at the boundary. Both now carry it. That is the difference between
  this taking six runs and the next one taking one.

**Try next:** for the remaining test, assert the priority row's own state rather than its
rendered label, to separate "the click did not reach the handler" from "the handler ran
and the composable did not observe". The DB snapshot already says the latter.

---
