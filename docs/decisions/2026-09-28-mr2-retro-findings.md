---
title: "MR-2 retro — a subtask bug the slot tests exposed, and what the split did not fix"
date: 2026-09-28
tags: [retro, tech-debt, tasks, tests]
status: accepted
---

Retro after the `TaskDetailViewModel` split. One critical bug was found and fixed; the rest
is recorded for the next iteration.

## Inventory

| Metric | Value |
|---|---|
| Production | 1 file deleted (524 LOC), 9 added (~840 LOC), 4 modified |
| Tests | 1 file deleted (255 LOC), 9 added (53 tests) |
| `:shared:jvmTest` | 1106 tests, 0 failed |
| detekt (shared + desktopApp) | 0 findings |
| ADRs | 1 new |

## CRITICAL — fixed in this MR

### R5 — "Add subtask" created a top-level task

`CreateTaskUseCase.invoke` re-validates through `TaskDomain.createInput(...)` and then builds
the task from that *new* input. It forwarded nine of the sixteen fields; `parentTaskId` was
not among them, so `buildTask` persisted a task with no parent. The user-visible effect: the
"add subtask" action on the task detail screen had never created a subtask.

Found because the new `TaskChildrenSlotTest` asserts the created task's `parentTaskId` rather
than only that a task appeared. The old suite had no coverage of `AddSubtask` at all, which is
why it survived.

**Fixed**: every field of `CreateTaskInput` is now forwarded, and the round-trip is documented
at the call site so the next omitted field is not silent. `startDate`, `startTime`, `endDate`,
`endTime`, `accentColor`, `emoji`, and `recurrence` were being dropped the same way.

## MEDIUM — not fixed, recorded

### R6 — R1 is still open, and this MR had to work around it
> **RESOLVED 2026-09-28 (roadmap MR-1):** This finding is the third ADR to carry the claim forward. It is not open and the three reverted attempts were chasing a premise that had expired. A probe test in MR-1 confirmed the fakes' `Unconfined` default already lets `advanceUntilIdle()` drain the chain; no fake was changed to get the slot suite onto virtual time. See `2026-09-28-mr1-test-virtualization-retro.md`.


`TaskDetailViewModelTest.TitleChanged debounce saves after delay()` was red on the branch
baseline (see the MR-1 retro). This MR deletes that file, so the red test is gone, but the
cause is not: the fakes' `FakeProfileAwareCurrentUser` still runs on `Dispatchers.Default`, so
every slot test pumps with real `delay()` instead of `advanceUntilIdle()`.

Three attempts at a scheduler-bound fake during this MR, all reverted:
- `StandardTestDispatcher(testScheduler)` — `stateIn` starts a `SupervisorJob` the test never
  drains, so `scopedUserId` never emits and every user-scoped read returns empty.
- `UnconfinedTestDispatcher()` — creates its own scheduler, which nothing advances; a `delay`
  inside it hangs.
- passing the `TestScope` directly — the internal `stateIn` never completes, so `runTest` fails
  with `UncompletedCoroutinesError`.

**The real fix is upstream of the fakes**: the repository observers must not depend on a
detached scope. That is a change to `FakeProfileAwareCurrentUser` and the `GenericUserScopedRepository`
observers, not to the tests. Until then, real-time pumping is the working pattern and every VM
test in the repo should use it consistently rather than each discovering it.

### R7 — `UpdateTaskUseCase.invoke(task)` is still deprecated and still used
> **RESOLVED 2026-09-28 (roadmap MR-1):** **Still open — a closure was recorded here on 2026-09-28 and was wrong.** The check
behind that closure was a grep for `updateTask.invoke`, which finds the two-argument form
`updateTask.invoke(id) { … }`. The offending call sites are `deps.updateTask(task.copy(…))`
— the single-argument form, with no `.invoke` — so the grep matched almost nothing and the
deprecation warnings were never actually inspected. A later full compile shows **7 call
sites across 5 slot files** still on the deprecated form: `TaskDraftSlot` (×2),
`TaskAiSlot` (×2), `TaskEntitySlot`, `TaskCompletionSlot`, `TaskChildrenSlot`.

The closure mistake is the general lesson: a compile warning is the evidence, not a grep
over the identifier you expect. See `2026-09-28-mr5-verification.md`.


Five call sites in the slots read the task from `taskFlow`, `copy()` one field, and write the
whole entity back. A concurrent remote edit to any other field is silently reverted. The
deprecation with the fix available dates from the earlier dispatcher/clock work. Migrating the
slots to `invoke(id) { copy(...) }` would also remove the need for the task-flow cache as a
write base.

## LOW — filed to the tracker

- `ProjectDetailViewModel` (335 LOC, 23 intents, 5-flow combine with three nested `flatMapLatest`,
  TOCTOU `_latestProject`) is the next coordinator-plus-slots candidate, now that the pattern and
  its test harness exist.
- `SearchViewModel` merges four flows through `listOf(...)` with an
  `@Suppress("UNCHECKED_CAST")`; `combineStates` removes it.
- `CalendarSyncViewModel` combines five flows, at the edge of the kotlinx overload set.
- `SettingsContributor` → `FeatureSlot` is still deferred. It needs `SettingsViewModel.bind()`
  and `dispatch()` edited in the same change, and `SettingsIntent` is not yet an `MviIntent`.
- Only 10 of the 30 task-detail intents have UI wired to them. The handlers are done and tested;
  the screen has not caught up.
