---
name: singularity-todo-preview-with-koin
description: Use when writing @Preview composables in this KMP project and the preview crashes with "KoinApplication has not been started". Documents the VM-as-parameter pattern (public screen = Koin wrapper, private content = accepts VM) and how to manually construct FakeRepositories for preview-time VM instantiation. Supersedes any approach that tries to start Koin inside a preview.
---

# @Preview Without Koin — VM-as-Parameter Pattern

Every screen in this project follows a two-composable pattern that makes previews work without any Koin context.

## The Pattern

```
@Composable  ← public, Koin entry point         ProjectDetailScreen(projectId, onBack, ...)
    │                                           koinViewModel { parametersOf(projectId) }
    │                                           ProjectDetailContent(viewModel, ...)
    ▼
@Composable  ← private, VM as parameter         ProjectDetailContent(viewModel, projectId, ...)
    │                                           (all real Compose UI)
    ▼
@Preview  ← manual VM construction            PreviewThemed {
    │                                               val vm = ProjectDetailViewModel(
@Composable                                                  projectId = sampleProjectId,
                                                         projectRepo = FakeProjectsRepository(...),
                                                         taskRepo = FakeTaskRepository(...),
                                                         ...
                                                     )
                                                     ProjectDetailContent(vm, ...)
                                                 }
```

**The public composable** (`ProjectDetailScreen`) is a thin Koin wrapper — it calls `koinViewModel { parametersOf(...) }` and delegates.

**The private content composable** (`ProjectDetailContent`) accepts the VM as a parameter. This is what `@Preview` instances call, with a manually constructed VM.

## Why This Works

- `@Preview` runs in an Android Studio / JVM test harness that does NOT start Koin
- The private composable has no Koin dependency — only the VM interface it receives
- FakeRepositories (`FakeTaskRepository`, `FakeProjectsRepository`, etc.) provide in-memory implementations with no Android/database dependencies

## Required Fake Doubles

All fakes live in `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeRepositories.kt`:

```kotlin
// FakeProjectsRepository — in-memory List<Project>, supports all watch* methods
// FakeTaskRepository     — in-memory List<Task>
// FakeSettingsRepository — in-memory key/value
// FakeNotesRepository   — in-memory List<Note>
// FakeProfileRepository  — returns a fixed Profile
// FakeProfileAwareCurrentUser — wraps FakeProfileRepository + FakeAuthRepository
```

## Preview Template

```kotlin
@Preview
@Composable
private fun ProjectDetailScreen_Preview() {
    PreviewThemed {
        val fakeTaskRepo = FakeTaskRepository()
        val fakeProjectsRepo = FakeProjectsRepository()
        val vm = ProjectDetailViewModel(
            projectId = sampleProjectId,
            projectRepo = fakeProjectsRepo,
            taskRepo = fakeTaskRepo,
            deleteProject = DeleteProjectUseCase(fakeProjectsRepo, fakeTaskRepo),
            updateProject = UpdateProjectUseCase(fakeProjectsRepo, Clock),
            updateTask = UpdateTaskUseCase(fakeTaskRepo),
            createTaskUseCase = CreateTaskUseCase(fakeTaskRepo, fakeProjectsRepo),
            clock = Clock,
            log = Logger,
        )
        ProjectDetailContent(
            viewModel = vm,
            projectId = sampleProjectId,
            onBack = {},
            onNavigateToTasks = {},
            onNavigateToTask = {},
        )
    }
}
```

> Example abbreviated — the real constructor takes every dependency explicitly. Copy the
> actual parameter list from the VM when writing a new preview.

## Rules

1. **Never call `koinViewModel()` inside `@Preview`** — it will crash with "KoinApplication has not been started"
2. **Public composables are `fun` (not `private`)** — they need Koin at runtime
3. **Private content composables are `private fun`** — they accept VM and are previewable
4. **FakeRepositories live in `commonMain`** — `commonTest` source set is not accessible from `commonMain` previews
5. **`Clock` in previews** — use `kotlin.time.Clock.System` and/or `core.platform.todayInSystemZone()`; the project-level `core.platform.Clock` object was removed (ADR `2026-09-27-remove-platform-clock-object.md`)

## What Changed (2026-09-09)

This skill replaces the abandoned `PreviewKoin` helper approach (which tried to start a Koin application inside a preview). That approach failed due to Koin DSL limitations in preview contexts. The VM-as-parameter pattern was chosen instead — it requires zero Koin infrastructure in previews and makes screens more testable.

See `docs/decisions/2026-09-09-preview-with-koin-helper.md` for the full decision record.

## Worked Example: `SavedAgendaEditScreen` — Reducer for Draft State

When a screen has local draft state (name being edited before save), a pure reducer makes the state transitions testable without DI:

```kotlin
// SavedAgendaEditState — the state shape
sealed interface SavedAgendaEditState {
    data object Loading : SavedAgendaEditState
    data class Editing(
        val view: SavedAgendaView,
        val editableName: String,
        val sectionCount: Int?,
        val isSaving: Boolean = false,
    ) : SavedAgendaEditState {
        val canSave: Boolean
            get() = !isSaving && editableName.isNotBlank() && editableName != view.name
    }
    data object NotFound : SavedAgendaEditState
}

// Reducer — pure function, testable without ViewModel
private object SavedAgendaEditReducer {
    fun reduce(state: SavedAgendaEditState, intent: SavedAgendaEditIntent): SavedAgendaEditState = when (intent) {
        is SavedAgendaEditIntent.NameChanged -> when (state) {
            is Editing -> state.copy(editableName = intent.name)
            else -> state
        }
        is SavedAgendaEditIntent.Save -> when (state) {
            is Editing -> state.copy(isSaving = true)
            else -> state
        }
        else -> state
    }
}
```

**The reducer is optional** — only add it when:
1. The screen has local draft state that accumulates before persisting
2. You want to write unit tests that call `reducer.reduce(state, intent)` with no mocks

If the screen has no local draft (e.g., `SavedAgendaListScreen` — it only passes state through), a reducer adds ceremony without value.

## Worked Example: `NotificationHost` Mapper with Sealed Events

When `NotificationHost` maps a feature-specific sealed event interface, the lambda's `it` is the sealed interface type — not the concrete subtype:

```kotlin
// WRONG — 'it' is SavedAgendaListEvent (sealed interface), has no 'message' property
mapper = { Notification.Error(it.message) }

// CORRECT — when expression for exhaustive subtype matching
mapper = { e ->
    when (e) {
        is SavedAgendaListEvent.ShowError -> Notification.Error(e.message)
        else -> Notification.None
    }
}
```

The same applies to any lambda over a sealed interface — the lambda parameter type is the interface, not the implementing data class.
