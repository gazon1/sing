---
status: accepted
date: 2026-09-18
---

# Mutation-result handling in ViewModels


> **Superseded in part (2026-09-27):** `StatefulViewModel`, `updateStateAs<T>`,
> `onStateChanged` and `CoroutineScope.fireAndForget` were removed. See
> [2026-09-27-mvi-single-state-entry-and-vm-sweep.md](2026-09-27-mvi-single-state-entry-and-vm-sweep.md).

## Context

A code review identified two classes of correctness bugs across 12 ViewModels in `feature/**/presentation/viewmodel/`:

1. **Silent failures** — 37 sites where a repository mutation (`repo.create()`, `repo.update()`, `repo.delete()` etc.) is called inside `scope.launch { ... }` and its `Result.failure` is caught only by `catch { log }`. Failures are dropped without any user-visible feedback.

2. **Crash sites** — 5 sites where `.getOrThrow()` is called on a `Result`, crashing the app when the operation fails:
   - `NotesListViewModel` lines 141, 149 (`setPinned`, `archive`)
   - `NotePreview` line 69 (`softDelete`)
   - `NoteEditor` lines 139, 195 (`createWithContent`, `persist`)

Additionally, several VMs use `MutableSharedFlow` for one-shot UI events. `MutableSharedFlow` is multi-subscriber by design — an event emitted while no collector is active is silently dropped. For single-consumer screen events this is unnecessary overhead.

## Decision

### 1. `fireAndForget` helper

Introduce `CoroutineScope.fireAndForget` in `core/coroutines/FireAndForget.kt`:

```kotlin
inline fun CoroutineScope.fireAndForget(
    errorLabel: String = "Operation failed",
    noinline onError: (Throwable) -> Unit,
    crossinline block: suspend () -> Result<*>,
): Job = launch {
    try {
        block().onFailure(onError)
    } catch (e: Throwable) {
        throw e  // Don't broad-catch
    }
}
```

Design decisions:
- **Does NOT broad-catch unchecked exceptions.** Programming errors / unexpected state propagate to the scope's `CoroutineExceptionHandler`. Callers must use `Result`-returning APIs.
- **`errorLabel`** names the call site (e.g. `"Delete failed"`). The label is passed explicitly so every call site is self-documenting.
- **`onError`** is a routing lambda — the caller decides whether to emit to a channel, update a state field, or log. No magic inside the helper.
- **Returns `Job`** — callers that need cancellation (e.g. autosave debounce) can call `job.cancel()`.

### 2. Repository contract

All mutation methods in repository interfaces MUST return `Result<T>` (not nullable `T`). `getOrThrow()` is forbidden in presentation and domain layers. Domain/use-case layers may call `.getOrThrow()` only when the caller explicitly requests a crash-on-failure behaviour (e.g. schema migrations).

### 3. One-shot event channels — `Channel(BUFFERED)` over `MutableSharedFlow`

VMs that emit one-shot single-consumer UI events (navigation, snackbar, undo toast) MUST use `Channel<UiEvent>(Channel.BUFFERED)` instead of `MutableSharedFlow`. `Channel.BUFFERED` provides at-least-once delivery on resumption, which covers the late-collecting-screen case.

VMs that genuinely need multi-subscriber events (AI result streams shared between multiple collectors) MAY keep `MutableSharedFlow`.

#### Per-VM channel table

| ViewModel | Channel type | Notes |
|---|---|---|
| `TasksViewModel._events` | `Channel<TasksUiEvent>(BUFFERED)` | |
| `TaskDetailViewModel._events` | `Channel<TaskDetailUiEvent>(BUFFERED)` | |
| `TaskCreateViewModel._saved` | `Channel<Unit>(BUFFERED)` | existing |
| `NoteEditor._events` | `Channel<NotesUiEvent>(BUFFERED)` | existing `_savedPulse` stays SharedFlow |
| `NoteEditor._savedPulse` | `MutableSharedFlow<Unit>` | multi-subscriber pulse signal |
| `NotesListViewModel._events` | `Channel<NotesUiEvent>(BUFFERED)` | new; replaces silent failures |
| `NotePreview._events` | `Channel<NotesUiEvent>(BUFFERED)` | new; replaces `.getOrThrow()` |
| `ProjectsViewModel._events` | `Channel<ProjectsUiEvent>(BUFFERED)` | |
| `ProjectDetailViewModel._events` | `Channel<ProjectDetailUiEvent>(BUFFERED)` | |
| `ProjectEditorViewModel._events` | `Channel<ProjectEditorUiEvent>(BUFFERED)` | |
| `SavedAgendaListViewModel._events` | `Channel<SavedAgendaListEvent>(BUFFERED)` | |
| `SavedAgendaViewModel._events` | `Channel<SavedAgendaEvent>(BUFFERED)` | |
| `AgendaViewModel._events` | `Channel<AgendaUiEvent>(BUFFERED)` | |
| `CalendarViewModel._events` | `Channel<CalendarUiEvent>(BUFFERED)` | |
| `ArchiveViewModel._events` | `Channel<ArchiveUiEvent>(BUFFERED)` | |
| `AuthViewModel._events` | `Channel<AuthUiEvent>(BUFFERED)` | |
| `BackupViewModel._events` | `Channel<BackupUiEvent>(BUFFERED)` | |
| `AttachmentsViewModel._events` | `Channel<AttachmentsUiEvent>(BUFFERED)` | |
| `ChatViewModel._events` | `Channel<ChatUiEvent>(BUFFERED)` | |
| `TagsViewModel._events` | `Channel<TagUiEvent>(BUFFERED)` | |
| `SearchViewModel._events` | `Channel<SearchUiEvent>(BUFFERED)` | |

SettingsViewModel, ProfileSwitcherViewModel, ChecklistEditorViewModel — state-based; errors surface via `errorMessage: String?` field.

### 4. Error event naming convention

All `UiEvent.Error` variants use the message format `"<action> failed: <e.message ?: "unknown">"` (e.g. `"Delete failed: task not found"`). This is terse but specific. For state-based VMs the field is `errorMessage: String?`.

### 5. Error dismissal

Every VM that surfaces errors MUST expose a `DismissError` intent (or equivalent) that clears the error state. Screens wire this to `LaunchedEffect(state.errorMessage) { snackbar.showMessage(...); vm.dismissError() }`.

## Rationale

- **`Channel(BUFFERED)` vs `SharedFlow`**: `SharedFlow` is multi-subscriber; `Channel` is single-consumer. Every VM in this codebase backs exactly one screen, so the multi-subscriber property of `SharedFlow` is unused overhead. `Channel.BUFFERED` with size 1 would drop the oldest element on overflow — `BUFFERED` (unbounded) is preferred to avoid silent drops. The at-least-once guarantee on resume matches the late-collector semantics of screen lifecycle.
- **`fireAndForget` design**: Named after the C# pattern. Returning `Job` is intentional — some callers (autosave debounce, undo windows) need cancellation. The helper is small enough to be inlined at call sites without indirection cost.
- **No broad-catch**: Catching all `Throwable` would swallow `CancellationException` (used by `withTimeout`, `Job.cancel`, structured concurrency) and programming errors (`NullPointerException`, `IllegalStateException`). These MUST propagate.

## Consequences

- Every `_events.emit(x)` in VM code becomes `_events.trySend(x).isSuccess` (fire-and-forget) or `_events.send(x)` (back-pressure when needed).
- Exposed `events: Flow<UiEvent>` becomes `_events.receiveAsFlow()`.
- VM tests using `turbine` on `_events` need migration to `flow.test {}` from `kotlinx-coroutines-test`.
- `applyRoute` in `TasksViewModel` is dead code — zero callers confirmed; deleted.
- `getOrThrow()` removed from 5 VM sites; replaced with `fireAndForget` + channel emit.

## Links

- Related: [`2026-09-18-vm-migration-scope-injection`](2026-09-18-vm-migration-scope-injection.md) — canonical VM scope injection pattern.
- Supersedes: implicit convention of `.emit()` on `MutableSharedFlow` for one-shot events.
