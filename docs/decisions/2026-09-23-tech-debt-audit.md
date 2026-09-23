---
title: "Tech debt audit — post vm-event-guard-cleanup"
date: 2026-09-23
tags: [tech-debt, audit, vm, database, tests]
status: accepted
---

## Context

After completing PR #2 (`feat/vm-event-guard-cleanup`) a batch audit surfaced residual issues not yet scoped into any PR. This ADR records them for tracking.

---

## 1. `e37bbdb` merge commit leaves main in broken state

**Severity: Critical (breaks compilation)**

The merge commit `e37bbdb` (Merge feat/search-query-language into main) contains unresolved merge conflicts in multiple files:

| File | Issue |
|---|---|
| `Migrations.kt` | `Migration16To17` referenced but not defined inline |
| `Migration15To16.kt` | Duplicate of inline definition in `Migrations.kt` — redeclaration |
| `Migration17To18.kt` | Duplicate of inline definition in `Migrations.kt` — redeclaration |
| `FakeAppDatabase.kt` | Unresolved `<<<<<<< HEAD` conflict markers |

**Impact:** KSP `PROCESSING_ERROR` when compiling; `e37bbdb` is the current tip of `main` and `merge-test` branches in the main checkout.

**Fix:** Delete `Migration15To16.kt` and `Migration17To18.kt`, ensure `Migration16To17` is defined inline in `Migrations.kt`, remove conflict markers from `FakeAppDatabase.kt`.

**Owner:** quick-fix PR, no dependencies.

---

## 2. Pre-existing test failures in `SavedAgendaViewModelTest`

**Severity: High (failing tests)**

3 tests in `SavedAgendaViewModelTest` fail with `UncompletedCoroutinesError`. Confirmed via `git stash` — failures exist before any of the `vm-event-guard-cleanup` changes.

**Impact:** CI is red; regression detection is unreliable.

**Fix:** Investigate `SavedAgendaViewModel` coroutine scope management. Likely `scope.launch` without `repeatOnLifecycle` or premature scope cancellation.

**Owner:** separate PR, no dependencies.

---

## 3. god-VM: `SettingsViewModel` and `ProjectDetailViewModel`

**Severity: High (maintenance drag)**

Both VMs have 300+ line constructors and handle 5+ unrelated feature areas. Any new setting or project field grows these further.

| VM | Lines | Feature areas |
|---|---|---|
| `SettingsViewModel` | ~340 | profiles, notifications, AI, backup, about |
| `ProjectDetailViewModel` | ~340 | project CRUD, task list, subtasks, tags, sort |

**Fix:** Extract sub-ViewModels per feature area, similar to how `AttachmentsViewModel` is already separate from `TaskDetailViewModel`.

**Owner:** separate PR, after quick-fix PR #1.

---

## 4. `TaskRepositoryImpl` — no read-before-write guard

**Severity: Medium (data integrity)**

`UpdateTaskUseCase` was fixed to re-read before write (PR #2). But `TaskRepositoryImpl.update(Task)` and `ProfileRepositoryImpl` call the DB write directly without re-reading. Any caller that bypasses the use case layer can overwrite concurrent external changes.

**Fix:** Add re-read in repository `update()` implementations, or enforce that all writes go through use cases (currently not enforced by architecture).

**Owner:** separate PR, or part of sync PR.

---

## 5. `repeatOnLifecycle` missing in Compose VMs

**Severity: Medium (resource leak / crash risk)**

All ViewModels use `scope.launch { }` without `repeatOnLifecycle(STARTED)`. This means:

- Launches can continue after the UI is stopped (resource leak)
- On config change, coroutines launched in `init` may outlive the new UI cycle

**Impact:** Potential memory leaks, duplicate work, crashes on severe memory pressure.

**Fix:** Replace `scope.launch { }` with `scope.launch { repeatOnLifecycle(STARTED) { ... } }` in all VM constructors and `onIntent` handlers. Large-scale migration across 20+ VMs.

**Owner:** separate PR (many files), after god-VM split.

---

## 6. `PendingTaskSaves` — no per-task write serialisation

**Severity: Medium (data integrity under concurrent edit)**

If a user opens the same task on two devices and saves concurrently, the last write wins with no merge. The sync engine handles conflict resolution for external changes, but there is no per-task lock on the local save path.

**Fix:** Design a `SaveLock` or `PendingTaskSaves` map that serialises `save()` calls per `TaskId`. Needed only if simultaneous multi-device edit is a real scenario.

**Owner:** separate PR, requires design decision.

---

## 7. `CalendarSyncOrchestrator` — `Channel<UNLIMITED>`

**Severity: Low (potential memory growth)**

Internal pipe between sync engine components uses `Channel.UNLIMITED`. If the writer produces faster than the reader consumes, memory grows unbounded.

**Impact:** Long-running sync with paused consumer could balloon memory.

**Fix:** Replace with `Channel(CONFLATED)` or bounded channel with backpressure strategy.

**Owner:** separate PR, requires design decision.

---

## 8. `Migration16To17` — verify definition in `Migrations.kt`

**Severity: Low (silent KSP failure risk)**

After removing duplicate `Migration15To16.kt` and `Migration17To18.kt`, `Migration16To17` must exist exactly once inline in `Migrations.kt`. If it was the duplicate declaration (and the real definition was the one that should be kept), it may have been accidentally removed.

**Action:** Audit `Migrations.kt` to confirm all 7 migration classes (5-6 through 17-18) are present exactly once. Run KSP to confirm no `PROCESSING_ERROR`.

**Owner:** quick-fix PR #1 (same as item 1).

---

## Prioritised stack

```
PR #1 (quick-fix):
  ├── Item 1: Fix e37bbdb broken files (migrations, FakeAppDatabase)
  ├── Item 8: Verify Migration16To17 definition
  └── Item 2: Fix SavedAgendaViewModelTest

PR #2 (god-VM split):
  └── Item 3: Extract SettingsViewModel sub-VMs
  └── Item 3: Extract ProjectDetailViewModel sub-VMs

PR #3 (data integrity):
  └── Item 4: read-before-write in TaskRepositoryImpl / ProfileRepositoryImpl

PR #4 (coroutine hygiene):
  └── Item 5: repeatOnLifecycle migration (all VMs)

Future (requires design):
  └── Item 6: PendingTaskSaves per-task lock
  └── Item 7: CalendarSyncOrchestrator backpressure
```

---

## Links

- PR #2: `feat/vm-event-guard-cleanup` — concurrent save guard, typed combine, dead code removal
- Decision `2026-09-23-vm-event-guard-cleanup` — what was done and explicitly not done
- Skill `singularity-todo-vm-migration-playbook` — canonical VM constructor pattern
- Skill `singularity-todo-clean-architecture-audit` — layer boundary grep checks
