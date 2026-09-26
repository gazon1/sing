---
title: Epic 2 roadmap — architecture phase of the tech-debt sprint
date: 2026-09-26
status: accepted
tags: [tech-debt, roadmap, epic2, testing, vm]
epic: refactor/techdebt-epic2-v2
---

# Epic 2 Roadmap

Worktree: `/home/max/worktrees/singularity-epic2`, branch `refactor/techdebt-epic2-v2`
(stacked on `refactor/techdebt-preflight` — main checkout has uncommitted parallel
work, so per the worktree-isolation skill epics are stacked instead of branching
from main).

## PR 2.1 — VM test debt unblocking (this PR)

One root cause broke 6 of the 14 slow-tagged test classes: the `testScope()`
helper wraps the VM scope in a **child `Job` that never completes**, so every
test creating a VM failed with `UncompletedCoroutinesError` at body end.
Secondary cause: coroutines 1.11's `backgroundScope` tasks **do not execute
under `advanceUntilIdle()`/`runCurrent()`** — they run only when the test body
suspends (`delay`) or when virtual time advances (`advanceTimeBy`). Verified by
a wiring matrix (foreground/background × direct/wrapped × pump style).

### Canonical wiring decision (per VM type)

| VM launches | Scope wiring | Virtual-time pump |
|---|---|---|
| Completing work only | `AutoCloseableCoroutineScope(testScope.coroutineContext)` — direct, no child Job | `advanceUntilIdle()` |
| Infinite collectors | `AutoCloseableCoroutineScope(backgroundScope.coroutineContext)` — direct background child | `advanceTimeBy(N); runCurrent()` after each intent (background tasks skip `advanceUntilIdle`; `advanceTimeBy` boundary excludes the current instant, hence `runCurrent`) |
| Infinite collectors + per-test teardown | child-Job `testScope(scope)` + explicit `vmScope.job?.cancel()` (SyncViewModel) | `advanceTimeBy(N); runCurrent()` |

`advanceUntilIdle` stays valid for foreground, completing-only VMs.

### Production bugs found and fixed (tests documented the intended contract)

1. **`SyncViewModel.syncNow` debounce never worked** — the `isLoading` guard was
   checked inside `syncMutex.withLock`; a second SyncNow queued on the mutex and
   acquired it only after the first call's `finally` cleared `isLoading`, so
   every rapid double-tap ran sync twice. Fix: early guard before the lock,
   re-check under the lock.
2. **`NoteEditor.openEditor`/`createNote` never advanced `baseline`** —
   `updateDraft { draft }` immediately before `open(draft)` triggered the base
   class's `sameEntity` early-return, leaving `baseline` at the empty initial
   draft. `closeEditor()` (discard) reverted to an **empty** editor instead of
   the opened note. Fix: `open(draft)` first, then `updateDraft { draft }`.
3. **`DraftMviViewModel` had no post-autosave hook** — draft-local flags
   (`Editing.isDirty`, `Editing.isNew`) stayed set after content was persisted.
   Fix: `protected open fun onAutosaved(current: D)` called after a successful
   autosave; `baseline` deliberately NOT advanced (keeps `discard` semantics;
   enforced by `DraftMviViewModelTest.discard resets to baseline`). NoteEditor
   clears `isDirty`/`isNew` in its override; `onSaved` clears `isNew` after an
   explicit save.

### Test fixes

- Untagged 12 of the 14 slow classes (all green; the remaining 7 tags are
  legitimately slow disk/subprocess tests: AttachmentStorage, BackupCodec,
  FileLogWriter, FileSystem, AppDatabaseFactoryJvm, PlatformPragmas,
  DesktopRestartSmoke).
- `BackupOptionsTest`: hardcoded `"0.0.11"` → `appVersion().name` (version bump
  no longer breaks the suite).
- `AutoSyncTest`: removed nested `runTest` ("only a single call to runTest" —
  suspend setters called directly in the outer body).
- `SyncRepositoryCoalescingTest`: `runCurrent()` only — `advanceUntilIdle` let
  the first (yielding) sync finish, so the second call never observed `Pushing`.
- `ProjectsViewModelTest`: seed a project matching the query (empty/filtered
  repo emits `Empty`, which carries no `searchQuery` field).

## Remaining Epic 2 PRs (from roadmap V2.1)

- PR 2.2 VM lifetime sharing — **partially superseded**: MR-6/MR-6d already
  migrated 19/21 VMs to `MviViewModel`; re-scope to a policy audit, not a
  migration.
- PR 2.3 MviViewModel migration — re-scope after MR-6d: remaining hand-rolled
  VMs + the 21 `VmCloseable` detekt findings.
- PR 2.4a/2.4b God-VM splits (Settings ~340 lines, ProjectDetail).
- PR 2.5 Data integrity — read-before-write already landed via old
  `refactor/techdebt-epic2` (commit `a449f249`); remaining:
  `CalendarSyncOrchestrator.Channel(UNLIMITED)` → `CONFLATED`.

## Links

- Roadmap V2.1 (approved plan), `2026-09-26-preflight-quick-wins`
- `2026-09-25-test-flaky-root-causes` (predecessor findings)
- `2026-09-24-pr1-tech-debt-audit-resolution` (original SavedAgenda diagnosis)
