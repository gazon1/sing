---
title: "Delete ChecklistUseCase; drop unused ProfileAwareCurrentUser from AgendaDeps/CalendarDeps; inject taskId via ChecklistEditorViewModel constructor"
date: 2026-09-22
tags: [repository, checklist, currentuser, koin, refactor]
status: accepted
---

## Context

Three separate but related cleanup triggers converged in one PR:

1. **`ChecklistUseCase`** was flagged by the `PassThroughUseCaseRule` detekt rule (expression-body methods delegating to repository without adding logic). The `toggleItem(id, title, taskId, isCompleted)` overload also had a bug — it hardcoded `sortOrder = 0`, silently losing the item's position on every toggle.

2. **`ChecklistEditorViewModel`** had two orphaned patterns: a `bindToTask(taskId)` method with zero external callers, and a `ChecklistEditorIntent.Load` branch that was a no-op comment pointing to the removed method.

3. **`AgendaDeps`**, **`CalendarDeps`**, and **`SavedAgendaListViewModel`** each declared `currentUser: ProfileAwareCurrentUser` — confirmed by code review to be never read by their respective ViewModels.

## Decision

### ChecklistUseCase → ChecklistRepository

Deleted `ChecklistUseCase.kt`. Extended `ChecklistRepository` with two new semantic operations:

```kotlin
suspend fun addItem(taskId: String, title: String): Result<ChecklistItemId>
suspend fun toggleItem(taskId: String, itemId: ChecklistItemId): Result<Unit>
```

Both are implemented in `RoomChecklistRepository`. `addItem` generates `ChecklistItemId`, stamps `createdAt = updatedAt = clock.now().toEpochMilliseconds()`. `toggleItem` reads the existing entity, throws `IllegalArgumentException` if not found, upserts with `isCompleted = !current` — preserving `sortOrder`, `title`, and `taskId`.

`ChecklistEditorViewModel` and `TaskDetailViewModel` now call these methods directly on `ChecklistRepository`, matching the pattern established by `TaskRepository` (`toggleComplete`, `togglePinned`, `softDelete`).

### ChecklistEditorViewModel constructor change

Removed the `checklistUseCase` constructor parameter entirely. Added `taskId: String` as the first constructor parameter (canonical Koin pattern: `viewModel { (taskId: String) -> ChecklistEditorViewModel(taskId = taskId, checklistRepository = get()) }`). Moved the `watchByTask` collection from `bindToTask()` into `init {}`. Removed `bindToTask()`, `ChecklistEditorIntent.Load`, and the `taskId` field from `ChecklistEditorState`.

### Drop unused `ProfileAwareCurrentUser` from *Deps

Removed `currentUser: ProfileAwareCurrentUser` from `AgendaDeps` and `CalendarDeps`. Removed the corresponding `currentUser = get()` from `AgendaDiModule.kt` and `CalendarDiModule.kt`. Removed the unused `ProfileAwareCurrentUser` import from `SavedAgendaListViewModel.kt`.

## Rationale

**`ChecklistUseCase` deletion**: `ChecklistRepository` already owned `watchByTask` and `delete`. Adding `addItem`/`toggleItem` completes the CRUD surface and eliminates the pass-through layer. The bug in the old `toggleItem(id, title, taskId, isCompleted)` overload — `sortOrder = 0` on every toggle — is eliminated because the repository implementation preserves `existing.sortOrder` from the read entity. The `PassThroughUseCase` rule would flag any future expression-body pass-through on a `*UseCase` class.

**Constructor injection over side-effect method**: `bindToTask()` was the only path to initialise `taskId` in state. Making `taskId` a constructor parameter means the VM is correctly instantiated by Koin with `parametersOf`, and the `watchByTask` flow starts immediately in `init {}` — no sequencing bug where `onIntent` could fire before `bindToTask`.

**Unused `currentUser` removal**: Confirmed by exhaustive grep: `AgendaViewModel` and `CalendarViewModel` never read `deps.currentUser`. `SavedAgendaListViewModel` declared the import but the class itself never referenced it. Removing these fields reduces DI noise and prevents future confusion.

## Consequences

- `ChecklistRepository` is the single source of truth for checklist mutations. All consumers (VMs, AI tools) must use `addItem` / `toggleItem` / `upsert` / `delete` on the repository.
- `ChecklistEditorViewModel` is constructed with `taskId` via Koin `parametersOf`. Any existing call site that used `bindToTask()` is broken by design — that method no longer exists. Verify no production call site calls `bindToTask()` before merging.
- `ProfileAwareCurrentUser` remains in `feature/profile/` and is still injected into repositories (`TaskRepositoryImpl`, `RoomNotesRepository`, `AttachmentRepository`, `ReminderRepository`, `ProjectsRepositoryImpl`, `InternalLinkRepositoryImpl`, `RoomSavedAgendaViewsRepository`). It is **not** injected into presentation-layer VMs except where actually read.
- `GenerateChecklistUseCase` (AI feature, `feature/ai/use_cases/`) is a separate class and is not affected by this deletion.
- `TaskRepositoryImpl` does **not** yet stamp `userId` on `create` — that is PR 2 (Repository infrastructure). Until that lands, callers must still pass `userId`-stamped entities to `TaskRepository.create`.

## Links

- Commit: `refactor(checklist): delete ChecklistUseCase, route VMs through repository`
- Commit: `refactor(checklist): inject taskId into ChecklistEditorViewModel constructor`
- Commit: `refactor(presentation): drop unused ProfileAwareCurrentUser from AgendaDeps/CalendarDeps`
- Files deleted: `shared/src/commonMain/kotlin/com/singularity/todo/feature/checklist/ChecklistUseCase.kt`
- Files changed: `ChecklistRepository.kt`, `RoomChecklistRepository.kt`, `ChecklistEditorViewModel.kt`, `TaskDetailState.kt`, `TaskDetail.kt`, `TasksDiModule.kt`, `FakeRepositories.kt`, `TaskLifecycleIntegrationTest.kt`, `TaskDetailViewModelTest.kt`, `AgendaDeps.kt`, `CalendarDeps.kt`, `AgendaDiModule.kt`, `CalendarDiModule.kt`, `SavedAgendaListViewModel.kt`
- Related: [2026-09-26-internal-link-repo-currentuser](2026-09-26-internal-link-repo-currentuser.md) — same `ProfileAwareCurrentUser` injection cleanup on `InternalLinkRepository`
