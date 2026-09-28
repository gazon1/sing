---
title: TaskDetailViewModel Migration + Debounce Write-Loop Fix
date: 2026-09-27
status: accepted
deciders: Singularity Developer
deciders: Singularity Developer
---

> **Superseded in part (2026-09-28):** this ADR records that
> `FakeProfileAwareCurrentUser` "running on `Dispatchers.Default`" is why the slot
> suite cannot use virtual time. That was already false when written — `560f3bf8` had
> moved the default to `Dispatchers.Unconfined`, an eager dispatcher. A probe test in
> MR-1 confirmed `advanceUntilIdle()` drains the whole slot suite with no fakes
> changed. The row-4 follow-up is done: those `delay(100)` / `delay(50)` calls are
> gone, and the 300 ms debounce is crossed with `advanceTimeBy`.
> See [2026-09-28-mr1-test-virtualization-retro.md](2026-09-28-mr1-test-virtualization-retro.md).

# TaskDetailViewModel Migration + Debounce Write-Loop Fix

## Context

Second half of the MVI sweep (see
[2026-09-27-mvi-single-state-entry-and-vm-sweep.md](2026-09-27-mvi-single-state-entry-and-vm-sweep.md)).
Two things were left for their own MR: the last ViewModel outside the hierarchy, and a
test that had been failing — and hanging for 60 seconds — since before that ADR was
written.

`TaskDetailViewModel` was 523 lines in `TaskDetail.kt`, extended `ViewModel()` directly,
owned its own `Channel<TaskDetailUiEvent>`, its own `MutableStateFlow` state, and 7
auxiliary flows. Because it lived in a file not named `*ViewModel.kt`, **all four MVI
detekt rules silently skipped it** — the rule set had been warning about this exact
class of problem for an epic while the largest offender sat outside it.

## Idea

Move the last VM onto the base, close the detekt blind spot, and fix the failing test
at its actual root cause rather than working around it.

## Decision

**1. `TaskDetailViewModel` → `MviViewModel<TaskDetailUiState, TaskDetailIntent.Domain, TaskDetailUiEvent>`.**
Its `Channel` + `receiveAsFlow` became the inherited `events`; `_state` became `state`;
`scope.launch` became `vmScope.launch`; `_events.trySend` became the inherited `tryEmit`.
The 7 auxiliary flows (`_latestTask`, `_recentlyDeleted`, `_aiRunning`, `_linkedNotes`,
`_linkedTasks`, `_retryVersion`, the edit buffers) were deliberately left alone —
folding them is a redesign, and `onIntent` is already 249 lines of real debt that the
detekt baseline already tracks.

**2. Local `emitError(message)` renamed to `showError(message)`.** The class had its own
private `emitError(message: String)`, which would have shadowed the base class's
`emitError(errorLabel, errorEvent, block)`. Two same-named members with different
shapes is a trap; the local one keeps its role ("show this message as an error event")
under an honest name.

**3. Detekt gates moved from file name to class name.** The five rules gated on
`root.name.endsWith("ViewModel.kt")`. They now gate on the class name via a shared
`isViewModelClass` predicate — matching how `ViewModelMustHaveKDocRule` in
`KDocEnforcementRules.kt` already worked. A file named `Whatever.kt` no longer hides a
`WhateverViewModel`. This had to land in the same MR as the migration: on its own it
would have made `MviViewModelExt` and `VmCloseable` fire on the unmigrated class and
broken `just lint`.

**4. The debounce write loop was fixed (see below).** The failing test was a symptom.

## The bug the failing test was pointing at

`TitleChanged debounce saves after delay` had been failing since before this work began,
hanging the full 60 s `runTest` timeout. The test's own KDoc blamed
`FakeProfileAwareCurrentUser` running on `Dispatchers.Default` and pointed at
[2026-09-25-testable-vm-dispatcher-clock](2026-09-25-testable-vm-dispatcher-clock.md)
as the fix. That diagnosis was incomplete.

Instrumenting the scheduler step by step showed the hang landing exactly at virtual time
`t=300` — the moment the debounce fires. A temporary print inside the collector revealed
it was not hanging once. It was firing **forever**, with the same title, ~3 times per
second of virtual time.

The loop:

```
titleEdits ──debounce(300ms)──> combine ──> deps.updateTask(task.copy(title = title))
                                                            │
                              stamps a new updatedAt        │
                                                            ▼
                            taskRepo.observe(taskId) re-emits
                                                            │
                     _latestTask (a StateFlow) re-emits ────┘
                                                            │
                     combine emits (task, "Edited title") again
                                                            │
                                        ...one debounce window later, repeat
```

`debounce` was applied to `titleEdits` only, so the combine's output was never
debounced or deduplicated: the write's own observable side effect re-triggered it.

**This was not a test bug. In production, opening a task detail screen and typing one
character produced a continuous write loop** — each iteration bumping `updatedAt` and
thus feeding the sync engine, for as long as the screen stayed open.

The fix is a content guard — the re-triggered emission always carries a title that
already matches the stored one, so that is where the loop terminates:

```kotlin
.collect { (task, title) ->
    if (task.title != title) {
        deps.updateTask(task.copy(title = title))
            .onFailure { showError("Save failed") }
    }
}
```

Applied to both the title and description collectors. Note that `distinctUntilChanged()`
on the combine output would **not** have worked: the pair `(Task, String)` differs on
every pass because `updatedAt` changes, so the pair is never "equal". The guard has to
compare the field being written.

The test was then rewritten onto virtual time (`advanceTimeBy`), which the real
`delay(400)` never actually provided — inside `runTest` a `delay` is skipped instantly,
so the test was not waiting the 400 ms its comment claimed. A regression test pins the
fix: it advances 5 seconds of virtual time past the write and asserts `updatedAt` has not
moved again.

## Consequences

- **Every ViewModel in the project is now on `MviViewModel` or `DraftMviViewModel`.**
  The only remaining `ViewModel()` subclasses are the base classes themselves.
- The detekt MVI rules can no longer be evaded by file naming. All 29 `*ViewModel`
  classes were already correctly named, so the change added coverage for exactly one
  class — the one it was meant to cover.
- `onIntent` is still 249 lines with cyclomatic complexity over the limit. That debt is
  real and still baselined; it is now *visible* to the rules rather than invisible to
  them. See «Отложенные находки».
- The suite is green for the first time in this refactor: 1047 tests, 0 failures.

### Baseline handling

The 10 detekt baseline entries for `TaskDetail.kt` were re-keyed to
`TaskDetailViewModel.kt` rather than regenerating the baseline, and the two
`LongMethod` / `CyclomaticComplexMethod` IDs were updated to `override fun onIntent` to
match the new signature. This preserves the project's existing decision to tolerate
`onIntent`'s size instead of masking it with a fresh baseline. The now-obsolete
`Filename` entry was dropped.

## Отложенные находки

| # | Finding | Why deferred | Where it goes |
|---|---|---|---|
| 1 | `onIntent` is 249 lines, cyclomatic complexity over the limit | Real debt; a split is a redesign of the screen's intent surface and needs its own review | Separate PR |
| 2 | `TaskDetailViewModel` still owns 7 auxiliary flows (`_latestTask`, `_aiRunning`, `_linkedNotes`, `_linkedTasks`, `_retryVersion`, edit buffers) | Folded on the detail screen the same way as `ProjectDetail`/`NotesList` in the previous MR; kept out to keep this MR's diff reviewable | Separate PR |
| 3 | `internal val _latestTask` has no matching public property, so `BackingPropertyNaming` flags it (baselined) | It is a TOCTOU write-through cache that the debounce collectors read; renaming it is part of #2 | With #2 |
| 4 | The other 6 tests in `TaskDetailViewModelTest` still use real `delay(100)` / `delay(50)` inside `runTest` | They pass, and the class is provably drivable by virtual time now (the two rewritten ones are). Converting them is cosmetic but the pattern is the same hazard that produced this bug's 60 s hang | Optional follow-up |
| 5 | `FakeTaskRepository.currentUser` is a `get()` that constructs a **new** `FakeProfileAwareCurrentUser` — and a new `CoroutineScope` — on every access | Pre-existing; every `observeAll()` / `currentUserId()` call allocates a scope and launches a collector. Wasteful and a latent flake source, but unrelated to this MR and it does not fail | Separate PR |
| 6 | `just setup-hooks` + the `pre-commit` hook root (item 8/9 of the previous ADR) | Tooling, not MVI | Still open |

## Links

- [2026-09-27-mvi-single-state-entry-and-vm-sweep.md](2026-09-27-mvi-single-state-entry-and-vm-sweep.md)
  — the base-class reshape and the first three migrations
- [2026-09-25-testable-vm-dispatcher-clock.md](2026-09-25-testable-vm-dispatcher-clock.md)
  — the dispatcher-injection ADR. Its diagnosis pointed at the right class of problem;
  the actual cause turned out to be the feedback loop above, not the dispatcher
- [2026-09-08-projects-ux-rework.md](2026-09-08-projects-ux-rework.md) — silent debounced
  save, the behaviour whose loop this fix terminates
