---
title: "MR-1 retro — pre-existing red test, a rule that never existed, a deprecated TOCTOU API"
date: 2026-09-27
tags: [retro, tech-debt, tests, detekt]
status: accepted
---

Structured retro after MR-1 (framework foundation). Findings are grouped by the severity
rule from the epic: critical is fixed now, medium is recorded, low goes to the tracker.

## Inventory

| Metric | Value |
|---|---|
| Production files changed | 7 (3 new, 4 modified) |
| Test files | 3 new (16 + 25 tests), 2 updated |
| detekt findings (shared) | 48 → 0 after two auto-correct passes |
| detekt findings (desktopApp) | 0 |
| `:shared:jvmTest` | 1060 tests, 1 failed, 15 skipped |
| ADRs | 3 new |

## CRITICAL

### R1 — `TaskDetailViewModelTest.TitleChanged debounce saves after delay()` is red on the branch baseline

**Evidence.** Verified by stashing every MR-1 change to `TaskDetail.kt` and re-running: the
test fails identically. It is not a regression from this work.

**Symptom.** `UncompletedCoroutinesError: After waiting for 1m, the test body did not run to
completion`. The other six tests in the class pass.

**Root cause.** `FakeTaskRepository` and the `CreateTaskUseCase` behind it default to
`FakeProfileAwareCurrentUser`, whose internal scope is
`CoroutineScope(SupervisorJob() + Dispatchers.Default)`. `runTest` cannot advance a real
dispatcher. The VM's debounce fires on the test scheduler, but the write that follows awaits
the current user on `Dispatchers.Default`, so the coroutine never completes and the test times
out instead of asserting. The file's own KDoc documents this ("FakeProfileAwareCurrentUser …
runs on Dispatchers.Default … advanceUntilIdle() cannot virtualize it") but the file was
never converted.

**Status — deferred to the slot refactor.** `FakeProfileAwareCurrentUser` already accepts a
`dispatcher` parameter, and `FakeTaskRepository` accepts `explicitCurrentUser`, so the fix is
mechanical: build the fakes on `StandardTestDispatcher(testScheduler)` and replace `delay()`
with `advanceUntilIdle()`.

An attempt was made in MR-1 and **reverted**: constructing the fakes on a test dispatcher made
all seven tests fail on assertion rather than on timeout, which means the migration interacts
with something beyond the dispatcher (most likely `onIntent`'s
`_latestTask.value ?: return` early-out racing the first task emission). Spending more of
MR-1 on a file the slot refactor deletes entirely was not the right trade.

**Rule.** Slot tests in the follow-up must build every fake on `StandardTestDispatcher(testScheduler)`
in a per-test `setupFakes()` helper, sharing one `FakeProfileAwareCurrentUser` across them.
If an intent appears to no-op under `advanceUntilIdle()`, drain the state flow with
`advanceUntilIdle()` *before* dispatching the intent.

## MEDIUM

### R2 — `singularity-todo-detekt-rules-authoring` documents a rule that was deleted a day earlier

The skill's "Existing Rules" table listed `NoCombineSideEffectRule` (`no-combine-side-effect`).
`2026-09-26-detekt-rules-activation-audit.md` had **deleted** that file as an orphan — no
provider class, no ServiceLoader entry, no `detekt.yml` block, no tests — and stated "if
needed later, they must be rewritten with tests and proper registration". The skill was never
updated, so the table still advertised a rule that had been gone since the previous day. An
agent scoping work from the skill would have assumed the codebase was already protected.

Rewritten in MR-1, satisfying that condition exactly: provider as an inner class, ServiceLoader
entry, `detekt.yml` block, 25 tests, and a positive control against real code. Its first run
found a live bug in `TaskDetail.kt` — the same behaviour the rule was originally written for.

**Rule for the skill:** treat every "existing rules" table as an unverified claim. Confirm the
file, the ServiceLoader entry, and the `detekt.yml` block all exist before planning around it,
and check `docs/decisions/` for a removal before assuming a gap is accidental.

### R3 — `singularity-todo-testable-vm` lists VMs as migrated that are not

The skill's "Concrete VMs Following This Pattern" table claims `BackupViewModel` had a "Full
MviViewModel migration" at `feature/settings/presentation/viewmodel/BackupViewModel.kt`. It
was hand-rolled with its own `MutableStateFlow` + two `MutableSharedFlow`s, lived in
`feature/backup/`, and was the `MviViewModelExt` rule's only remaining production violator.
Migrated in MR-1. The same table's note that `TaskDetailViewModel` was already on
`MviViewModel` is also wrong — it extends `ViewModel` directly.

### R4 — `UpdateTaskUseCase.invoke(task)` is deprecated for a TOCTOU reason nobody acted on

Every VM write in `TaskDetail.kt` triggers:
`w: 'suspend fun invoke(task: Task): Result<Task>' is deprecated. Use invoke(id, transform) instead to avoid stale-snapshot overwrites.`

This is the whole `_latestTask` cache and the entire `TaskDetailDraftState` dance: the VM reads
a cached `Task`, `copy()`s a field on it, and writes the whole entity back, so any field changed
concurrently by a remote sync is silently reverted. The deprecation was added with the fix
available and five call sites still use the old form.

Scheduled for the slot refactor: the slots mutate through `updateTask(id) { copy(...) }`, which
removes the need for the cache entirely.

## LOW — filed to the tracker

- `TaskDetail.kt:142` assigns `_latestTask.value` inside `flatMapLatest`. Same side-effect-in-
  a-pure-operator family as the `combine` case, but the new rule is scoped to `combine` because
  `flatMapLatest`'s re-run trigger is the upstream emission, which is the intended semantics.
  Resolved by the slot refactor deleting the file.
- `SearchViewModel` merges four flows through `listOf(...)` with an
  `@Suppress("UNCHECKED_CAST")` unpack. `combineStates` removes it.
- `CalendarSyncViewModel` combines five flows — at the edge of the kotlinx overload set.
  `combineStates` makes the sixth free.
- `ProjectDetailViewModel` (335 LOC, 23 intents, 5-flow combine with three nested
  `flatMapLatest`) is the next coordinator-plus-slots candidate.
