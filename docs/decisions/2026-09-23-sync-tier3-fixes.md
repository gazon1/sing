---
description: Tier 3.5 fix pass — sync UI bugs, sync integrity issues, and cross-feature VM bug consolidation.
status: accepted
---

# ADR: Sync Tier 3.5 Fixes & Non-Settings Consolidation

## Context

After the Tier 3 sync PR (`2026-09-23-sync-pull-handlers-and-ui.md`) landed, a code-review pass identified several remaining issues in the sync flow plus some non-sync bugs in adjacent features. Settings-related bugs were deliberately excluded (handled in a separate branch per user direction).

### Sync UI Bugs (FIX 1–7, 9, 16, 19)

1. **`SyncViewModel.effects` SharedFlow unused** — dead code; error delivery relied on the `effects` flow but the `SyncConfigScreen` had no collector.
2. **`state.errorMessage` never set** — VM observed `SyncEngineStatus.Failure` but never wrote the error into `state.errorMessage`.
3. **`AcknowledgeError(AppError)` wrong type** — Intent carried an `AppError` param, but the screen wired `state.errorMessage!!.let { AppError.Unknown(...) }` to it (UI doesn't have the original AppError).
4. **Slider `steps=3` produced fractional values** — `Slider(steps=3)` gives 4 stops (15, 50, 84, 120), not the intended 4 discrete values.
5. **No snackbar collector in `SyncConfigScreen`** — error banner was the only error UI; no auto-dismiss.
6. **`Test connection` button was a no-op** — `onClick = { /* TODO: test connection */ }`.
7. **Failure snackbar race condition** — `allowSnackbarOnFailure` is reset before snackbar can fire on second consecutive failure.
9. **`syncNow()` not debounced inside the launch** — pressing twice in quick succession launches two parallel `syncOnce()` calls.
16. **`SyncButton` rotation animation restarted on recomposition** — `rememberInfiniteTransition` runs as long as the call-site is composed; a status change during animation discards the in-flight transition.
19. **`syncOnce()` exception escapes the launch block** — `try { ... } finally { ... }` had no `catch`, so a thrown exception (e.g. `getOrThrow()` on a failed `Result`) crashed the coroutine and surfaced as an uncaught test failure.

### Sync Integrity Bugs (FIX 8, 11, 12, 20)

8. **`SyncBootstrapper.handleDeleted` is a stub** — silently drops DELETED events without applying them; the engine thinks the local entity still exists.
11. **No `testConnection()` API** — users have no way to verify sync server connectivity without triggering a full sync cycle.
12. **`lastSuccessfulSyncAt` only updated on push** — `pull()` updates `lastLsn` but never stamps `lastSuccessfulSyncAt`, so the UI shows a stale timestamp after a successful pull.
20. **`decodeFromString(data.toString())` instead of `decodeFromJsonElement(obj)`** — the latter is the correct kotlinx.serialization idiom and avoids a redundant toString→parse round-trip.

### Cross-Feature VM Bugs (consolidation)

| Bug | File | Fix |
|---|---|---|
| `_aiResult: MutableSharedFlow<String>` dead collector | `ProjectsViewModel.kt` | Remove the field and its emit call (also drops `MutableSharedFlow` import) |
| `ArchiveScreen` "Archive all" no debounce | `ArchiveScreen.kt` | `enabled = !refreshing` guards the button while a refresh is in flight |
| `FakeTagsRepository.delete` hard-deletes | `FakeRepositories.kt` | Soft-delete via `existing.copy(deletedAt = Instant.fromEpochMilliseconds(0))` (matches production behavior) |
| `ReminderTile.SimpleDateFormat` constructed every recomposition | `ReminderTile.kt` | Wrap in `remember { ... }` |

### Architectural Self-Review (vs v1 plan)

The first draft of the plan had three issues that contradict project conventions:

1. **Proposed `SharedFlow<SyncEffect>` for one-shot errors** — but the existing convention in `SettingsViewModel` is state-embedded `errorMessage: String?` + `AcknowledgeError` Intent. Using a separate flow here would create a third, inconsistent pattern.
2. **Proposed `softDeleteByEntityId(String)` on repositories** — but boundary convention is: convert `String → typedId` in the consumer (`SyncBootstrapper`), call `repo.delete(typedId)`. Adding a String-typed method to repositories leaks JSON identifiers into the domain layer.
3. **No debounce on `syncNow()`** — but the project pattern is `if (current.isLoading || current.status.isRunning()) return@launch` *inside* `scope.launch { ... }`. This is used elsewhere (e.g. `AuthViewModel` sign-in).

## Decision

### 1. State-embedded error pattern (FIX 1, 2, 3, 19)

`SyncState.errorMessage: String?` becomes the single source of truth for errors. The `SyncEffect` sealed interface and `_effects` SharedFlow are removed. `AcknowledgeError` becomes `data object AcknowledgeError` (no param). `TestConnection` is added as a new Intent; results live in `state.isTestingConnection: Boolean` and `state.connectionTestResult: ConnectionTestResult?`.

The catch block in `syncNow()` now handles thrown exceptions:

```kotlin
try {
    repository.syncOnce()
} catch (e: Throwable) {
    allowSnackbarOnFailure = true  // ensure snackbar fires
    _state.update {
        it.copy(
            isLoading = false,
            status = SyncEngineStatus.Failure(e as? AppError ?: AppError.Unknown(e)),
        )
    }
    return@launch
} finally {
    _state.update { it.copy(isLoading = false) }
}
```

### 2. Debounce inside `scope.launch` (FIX 9)

```kotlin
private fun syncNow() {
    scope.launch {
        if (_state.value.isLoading || _state.value.status.isRunning()) return@launch
        // ...
    }
}
```

The guard runs **inside** the launch, so two quick taps result in two early-returns (only one reaches `repository.syncOnce()`).

### 3. Slider explicit snap (FIX 4)

Material3 `Slider(steps = N)` produces `N + 2` stops, not `N` stops. The correct way to get exactly `[15, 30, 60, 120]` is to use no `steps` param and quantize in `onValueChangeFinished`:

```kotlin
private val SNAP_VALUES = listOf(15, 30, 60, 120)
Slider(
    value = sliderValue,
    onValueChange = { sliderValue = it },
    onValueChangeFinished = {
        val snapped = SNAP_VALUES.minByOrNull { kotlin.math.abs(it - sliderValue.toInt()) } ?: 30
        viewModel.process(SyncIntent.SetInterval(snapped))
    },
    valueRange = 15f..120f,
)
```

### 4. `testConnection()` API (FIX 11)

```kotlin
// SyncRepository.kt
sealed interface ConnectionTestResult {
    data object Success : ConnectionTestResult
    data class Failure(val error: AppError) : ConnectionTestResult
}

interface SyncRepository : AutoCloseable {
    suspend fun testConnection(): ConnectionTestResult
    // ...existing
}

// SyncApi.kt
interface SyncApiClient {
    // ...existing
    suspend fun testConnection(userId: String): Result<Unit>
}

// SyncRepositoryImpl.kt
override suspend fun testConnection(): ConnectionTestResult {
    val session = authRepository.currentSession.value as? Session.SignedIn
        ?: return ConnectionTestResult.Failure(AppError.Validation("Not signed in"))
    return api.testConnection(session.userId.value).fold(
        onSuccess = { ConnectionTestResult.Success },
        onFailure = { ConnectionTestResult.Failure(it as? AppError ?: AppError.Unknown(it)) },
    )
}
```

The `SyncApiClient` returns `Result<Unit>`; the repository wraps it in `ConnectionTestResult`. The conversion lives in the repository layer — feature modules never see raw `Result<Unit>`.

### 5. `SyncBootstrapper` DELETED handler (FIX 8, 20)

The DELETED branch calls `repo.delete(TypedId.fromString(event.entityId))`. The conversion from `String` → typed ID happens at the boundary in `SyncBootstrapper`, not inside the repository. The decode uses `decodeFromString(serializer<T>(), data.toString())` (correct kotlinx.serialization API for `JsonObject`):

```kotlin
private suspend fun handleEvent(event: SyncEvent, applyRemote: suspend (JsonObject) -> Unit): ApplyOutcome {
    return try {
        when (event.eventType) {
            SyncEventType.CREATED, SyncEventType.UPDATED, SyncEventType.RESTORED -> {
                // ...applyRemote(obj)
            }
            SyncEventType.DELETED -> {
                val outcome: Result<Unit> = when (event.entityType) {
                    DocType.Task -> taskRepo.delete(TaskId.fromString(event.entityId))
                    DocType.Note -> noteRepo.delete(NoteId.fromString(event.entityId))
                    DocType.Project -> projectRepo.delete(ProjectId.fromString(event.entityId))
                    DocType.Tag -> tagRepo.delete(TagId.fromString(event.entityId))
                }
                // log + return Applied
            }
        }
    } catch (e: Throwable) {
        // log + return Conflict
    }
}
```

### 6. `recordSuccessfulSync()` in `pull()` (FIX 12)

```kotlin
// SyncEngine.pull() success branch
prefs.setLastLsn(maxLsn)
prefs.recordSuccessfulSync()  // NEW
```

### 7. `SyncButton` animation stability (FIX 16)

`rememberInfiniteTransition` runs as long as the call-site is composed. Without a `key` wrapper, a status change during animation disposes the transition (animation restart). The fix:

```kotlin
@Composable
fun SyncButton(status: SyncEngineStatus, ...) {
    key(status) {
        val infiniteTransition = rememberInfiniteTransition(label = "sync_rotation")
        val rotation by infiniteTransition.animateFloat(...)
        // ...Icon with rotation
    }
}
```

When `status` is unchanged, the inner block is preserved across recompositions and the animation continues uninterrupted. When `status` changes, the block is disposed and re-created (acceptable: a different icon is being shown anyway).

### 8. Cross-feature fixes

- `ProjectsViewModel._aiResult` removed; only `_events.trySend(ProjectsUiEvent.ProjectReviewResult(...))` remains.
- `ArchiveScreen` button gets `enabled = !refreshing`.
- `FakeTagsRepository.delete` mirrors production soft-delete.
- `ReminderTile.SimpleDateFormat` wrapped in `remember`.

## Consequences

- **State-embedded error pattern is now consistent** across `Settings`, `Sync`, and (already) `Backup` (the latter uses a typed `SharedFlow<UiEvent>` for navigation).
- **Two-tier snackbar pattern**: state-embedded `errorMessage` for transient errors, typed `SharedFlow<UiEvent>` for navigation. This is intentional — see `2026-09-21-state-embedded-errors.md`.
- **`testConnection()` requires `authRepository` + `api`** in `SyncRepositoryImpl`. `CoreDiModule` updated to pass both.
- **`SyncViewModel` constructor exposes `vmScope`** for tests; cancelling `vmScope.job` is the documented way to stop infinite collectors in test scope cleanup (child Job, does not cancel test body).
- **`AutoCloseableCoroutineScope.job` property** is now exposed; `testScope()` creates a child Job so test cleanup does not cancel the parent TestScope root.
- **The pull DELETED handler now propagates sync server deletes** to local repositories. Soft-delete is honored when the entity supports it (Tag, Project); hard-delete repos ignore the soft semantics.
- **Pull `lastSuccessfulSyncAt`** is now updated, so the "Last synced" UI field stays accurate after a successful pull.
- **Test fakes** (`FakeSyncRepository`, `FakeSyncApiClient`) gained the new methods to satisfy the interfaces.
- **All 5 `SyncViewModelTest` cases pass** under `:shared:jvmTest`. The pre-existing `DiGraphTest` failure (DataStore multi-instance on the same file) is unrelated to this PR.

## Out of Scope (separate branches)

- Settings: `FontSizeSlider` steps fix, `SettingsViewModel.errorMessage` audit, `ChecklistRepository` String→TaskId
- `AttachmentsViewModel.events` without consumer
- `FakeNotesRepository` / `FakeProjectsRepository` silent no-op on missing id
- `AuthViewModel.signIn` debounce
- `PomodoroScreen` Play/Pause race

## Links

- `SyncEngine.kt` — `pull()` with `recordSuccessfulSync()`
- `SyncApi.kt`, `SyncRepository.kt`, `SyncRepositoryImpl.kt` — `testConnection()` chain
- `SyncBootstrapper.kt` — DELETED handler + `decodeFromString(serializer<T>(), ...)`
- `feature/sync/presentation/SyncViewModel.kt` — sealed Intent refactor, debounce, catch block
- `feature/sync/presentation/SyncConfigScreen.kt` — slider snap, snackbar wiring
- `feature/sync/presentation/SyncButton.kt` — `key(status)` wrapper
- `core/di/CoreDiModule.kt` — DI wiring for new params
- `core/coroutines/AutoCloseableCoroutineScope.kt` — `job` property + test helper
- `feature/projects/presentation/viewmodel/ProjectsViewModel.kt` — removed `_aiResult`
- `feature/archive/ArchiveScreen.kt` — `enabled = !refreshing`
- `test/fakes/FakeRepositories.kt` — soft-delete fix
- `feature/reminders/ReminderTile.kt` — `remember { SimpleDateFormat(...) }`
- `jvmTest/.../feature/sync/SyncViewModelTest.kt` — 5 new test cases
