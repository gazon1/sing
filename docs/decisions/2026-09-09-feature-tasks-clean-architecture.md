---
title: "Feature/tasks Clean Architecture: domain/data/presentation layers"
date: 2026-09-09
tags: [architecture, clean-architecture, feature-tasks, kotlin-multiplatform]
---

## Context

`feature/tasks` had grown into a flat package with all types — domain logic, data implementations, presentation VMs, and UI screens — living side by side. The old structure:

```
feature/tasks/
├── TaskRepository.kt         # interface + impl mixed
├── TasksDomain.kt            # domain logic (mixed)
├── TasksUseCase.kt           # use cases (mixed)
├── TaskDetailViewModel.kt    # presentation
├── TaskEditorViewModel.kt    # presentation
├── TasksScreen.kt            # UI
└── components/ sections/     # UI (not separated)
```

Problems:
- **Cross-feature imports**: `presentation.something` could import `data.something` directly, bypassing domain
- **No layer discipline**: Room DAOs, business rules, and Compose UI all in the same package
- **Test coupling**: tests imported from the root package, brittle to refactoring
- **UserId duplication**: defined in `feature/tasks` but used across 10+ features

## Decision

Split `feature/tasks` into three layers with strict dependency rules:

```
feature/tasks/
├── domain/
│   ├── model/
│   │   ├── Task.kt               # TaskId, TaskPriority, TaskKind, TaskFilter, Task, CreateTaskInput
│   │   ├── TaskList.kt           # TaskGroup, TasksUiState, TasksUiEvent, AiActionResult, TaskAiAction
│   │   ├── TaskDetailState.kt    # TaskDetailUi, TaskDetailUiState, TaskDetailIntent, TaskDetailDeps
│   │   ├── TaskEditorState.kt    # TaskEditorMode, TaskEditorUiState, TaskEditorIntent, TaskEditorDeps, reduce()
│   │   ├── ActiveSheet.kt        # ActiveSheet sealed interface
│   │   └── AttachmentSaver.kt    # AttachmentSaver port interface
│   ├── port/
│   │   ├── TaskRepository.kt     # Repository interface (only!)
│   │   └── ReminderFormatter.kt # dueInstant(), parseDueTime()
│   └── usecase/
│       ├── CreateTask.kt         # CreateTaskUseCase
│       ├── UpdateTask.kt         # UpdateTaskUseCase
│       └── TaskMutations.kt      # TaskMutationsUseCase (bulkComplete, bulkDelete)
│
├── data/
│   └── TaskRepositoryImpl.kt     # Room implementation + toTask() mapper
│
└── presentation/
    ├── viewmodel/
    │   ├── TaskList.kt           # TasksViewModel
    │   ├── TaskDetail.kt         # TaskDetailViewModel
    │   └── TaskEditor.kt         # TaskEditorViewModel
    ├── screen/
    │   ├── TaskList.kt           # TasksScreen
    │   ├── TaskDetail.kt         # TaskDetailScreen
    │   └── TaskEditor.kt         # TaskEditorScreen
    ├── sheet/
    │   └── TaskDetailSheets.kt   # ConfirmArchiveSheet, ConfirmDeleteSheet, etc.
    └── components/
    └── sections/
```

**Dependency rule**: `presentation` → `domain` only. `data` → `domain` only. `domain` has no outward dependencies.

### Key design choices

| Decision | Rationale |
|---|---|
| `TaskRepository` interface in `domain/port/` | UI never talks to data directly; VM gets interface from domain |
| `TaskDetailDeps` / `TaskEditorDeps` data classes | Reduces constructor bloat; Koin wires via `get()` |
| `TaskEditorUiState.reduce()` in domain | Pure reducer — testable without Compose or Koin |
| `AttachmentSaver` port in domain | Data layer (Room attachments) behind a seam |
| `UserId` moved to `core/ids/` | Cross-feature ID type, not feature-local |
| `AppError` moved to `core/error/` | Shared error type across all features |

### What changed

- `Ids.kt` → `domain/model/Task.kt` (types split by domain)
- `TasksUiEvent.kt` + `TasksScreenEntry.kt` → `domain/model/TaskList.kt`
- `TaskDetailIntent.kt` + `TaskDetailUiEvent.kt` → `domain/model/TaskDetailState.kt`
- `TaskEditorViewModel.kt` types → `domain/model/TaskEditorState.kt`
- `TaskRepository.kt` (interface) → `domain/port/TaskRepository.kt`
- `TaskRepository.kt` (impl) → `data/TaskRepositoryImpl.kt`
- `TasksUseCase.kt` → `domain/usecase/CreateTask.kt` + `UpdateTask.kt`
- `TasksViewModel.kt` → `presentation/viewmodel/TaskList.kt`
- `TasksScreen.kt` → `presentation/screen/TaskList.kt`
- `AppError.kt` → `core/error/AppError.kt`

## Consequences

- **Positive**: Strict layer boundaries enforced by package structure; pure domain logic testable without Android instrumentation
- **Positive**: `TaskDetailUiState.reduce()` is a pure function — covered by unit tests without mocks
- **Positive**: Cross-feature imports are now compile-time errors if they bypass domain
- **Negative**: 40+ files had import paths updated; test files also required path corrections
- **Negative**: Deep `domain/model/` import chains if not careful (mitigated by `package com.singularity.todo.feature.tasks.domain.model.*`)

## Links

- Google Architecture: https://developer.android.com/topic/architecture
- `core/error/AppError.kt` — shared error types
- `core/ids/UserId.kt` — cross-feature ID type
