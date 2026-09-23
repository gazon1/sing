---
title: "Sync state model: public API, Result<T>, SyncRepository facade, AppError"
date: 2026-09-23
status: accepted
tags: [sync, architecture, core, state, ui]
---

## Context

`core/sync/SyncEngine` is built but inert: `enqueue()` has zero callers, `pull()` is a stub, `SyncEngineStatus` is a private `MutableStateFlow` never observed by UI, and `PushResult`/`PullResult` are custom data classes instead of Kotlin's idiomatic `Result<T>`. Meanwhile, Orgzly's sync UX patterns (rich `SyncState` sealed type, stateful button, `AutoSync` trigger taxonomy, `allowSnackbarOnFailure` debounce) are proven and worth adopting.

The app also needs a clean module boundary: feature ViewModels must not depend on `SyncEngine` directly.

## Decision

### 1. `SyncEngineStatus` — public sealed interface with failure collapsed to `AppError`

The status type is opened to public observation and simplified:

```kotlin
sealed interface SyncEngineStatus {
    data object Idle : SyncEngineStatus
    data object Pushing : SyncEngineStatus
    data object Pulling : SyncEngineStatus
    data object NoConnection : SyncEngineStatus  // offline — operational, not failure
    data class Failure(val error: AppError) : SyncEngineStatus

    fun isRunning(): Boolean = this is Pushing || this is Pulling
    fun isSuccess(): Boolean = this is Idle || this is NoConnection
}
```

`NoConnection` is a data object, not `Failure`, because offline is an operational state — the UI renders a banner ("Offline — changes will sync when connected"), not a snackbar. All other errors (auth expiry, server 5xx, outbox corruption) map to `Failure(AppError)`.

`SyncEngine._status` becomes `val status: StateFlow<SyncEngineStatus> = _status.asStateFlow()`.

### 2. `Result<PushSummary>` / `Result<PullSummary>` instead of custom result types

Standard Kotlin `Result<T>` replaces custom `PushResult`/`PullResult`:

```kotlin
data class PushSummary(val processed: Int, val succeeded: Int, val failed: Int)
data class PullSummary(val processed: Int, val applied: Int, val conflicts: Int)
```

`SyncEngine.push()` returns `Result<PushSummary>` via `runCatchingResult { … }` (from `core/error/AppError.kt`). On failure, `Failure(AppError)` is set via `.onFailure { _status.value = SyncEngineStatus.Failure(it as AppError) }`.

`SyncEngine._lastPush` → `val lastPush: StateFlow<Result<PushSummary>?>`.
`SyncEngine._lastPull` → `val lastPull: StateFlow<Result<PullSummary>?>`.

This matches the project's `Result.failure(AppError.X)` convention used in all use cases.

### 3. `AppError` for all sync errors — no new sealed type

`core/error/AppError.kt` is the single error type for sync. Existing variants cover the cases:

| Sync failure | Maps to |
|---|---|
| `IOException` / no network | `AppError.Network` |
| HTTP 401/403 | `AppError.Auth` (via extension below) |
| HTTP 5xx | `AppError.Unknown(IllegalStateException("HTTP $code"))` |
| Outbox corruption, decode error | `AppError.Persistence` |
| Unknown throwable | `AppError.Unknown` |

No new `SyncError` sealed type is introduced.

```kotlin
// In SyncEngine (internal)
private fun Throwable.toAppError(): AppError = when (this) {
    is AppError -> this
    is IOException -> AppError.Network(this)
    is HttpException -> when (statusCode) {
        in 400..403 -> AppError.Auth("$statusCode: ${message()}")
        in 500..599 -> AppError.Unknown(IllegalStateException("HTTP $statusCode: ${message()}"))
        else -> AppError.Unknown(this)
    }
    else -> AppError.Unknown(this)
}
```

If `AppError.Auth(message: String)` feels wrong (current signature is `AppError.Auth(message: String)` with `RuntimeException(message)` base), we add `AppError.Auth(val code: Int, val body: String? = null)` in a future ADR. **Decision deferred**: use `AppError.Auth("$code")` for now.

### 4. `SyncRepository` as the public facade

Feature modules never see `SyncEngine` or `SyncRunner`. They inject `SyncRepository`:

```kotlin
interface SyncRepository : AutoCloseable {
    val status: StateFlow<SyncEngineStatus>
    val lastPush: StateFlow<Result<PushSummary>?>
    val lastPull: StateFlow<Result<PullSummary>?>

    suspend fun enqueue(entity: SyncableEntity): Result<Unit>
    suspend fun syncOnce(): SyncOutcome
    fun startScheduledSync(interval: Duration)
    fun stopScheduledSync()
}

data class SyncOutcome(
    val push: Result<PushSummary>,
    val pull: Result<PullSummary>,
)
```

`SyncEngine` and `SyncRunner` are `internal class` (Gradle module visibility). Feature modules cannot import them — enforced by module boundary.

`SyncRepositoryImpl` wires: `SyncEngine`, `SyncScheduler`, `AuthRepository`, `SyncPrefs`.

### 5. `SyncRunner` — polling orchestration, extracted from `SyncEngine`

`SyncEngine` no longer launches its own coroutine on init. That responsibility moves to `SyncRunner`:

```kotlin
internal class SyncRunner(
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val authRepository: AuthRepository,
    private val prefs: SyncPrefs,
    scope: AutoCloseableCoroutineScope,
) : AutoCloseable by scope {

    val status = engine.status
    val lastPush = engine.lastPush
    val lastPull = engine.lastPull

    private var scheduledJob: Job? = null

    fun startScheduledSync(interval: Duration) {
        scheduledJob?.cancel()
        scheduledJob = scope.launch {
            while (isActive) {
                if (authRepository.currentSession.value is Session.SignedIn) {
                    engine.syncOnce()
                }
                delay(interval)
            }
        }
    }

    fun stopScheduledSync() { scheduledJob?.cancel(); scheduledJob = null }

    suspend fun syncOnce() {
        if (authRepository.currentSession.value !is Session.SignedIn) return
        engine.syncOnce()
    }
}
```

`SyncEngine.init { … }` block (the 30-second polling loop at `SyncEngine.kt:60-83`) is **removed**.

### 6. UI patterns from Orgzly

#### `SyncTrigger` + `AutoSync`

```kotlin
enum class SyncTrigger {
    Created,    // entity created → push immediately
    Updated,    // entity updated → push immediately
    AppResumed, // app foregrounded
    AppSuspended, // app backgrounded
    Scheduled,  // periodic timer fired
    NetworkConnected, // wifi reconnected (optional)
}

class AutoSync(
    private val prefs: SyncPrefs,
    private val repository: SyncRepository,
    private val scope: CoroutineScope,
) {
    fun trigger(t: SyncTrigger) {
        if (!prefs.autoSyncEnabled) return
        if (t !in prefs.enabledTriggers) return
        scope.launch { repository.syncOnce() }
    }
}
```

#### `allowSnackbarOnFailure` debounce

```kotlin
// In SyncViewModel
private var allowSnackbarOnFailure = false

init {
    viewModelScope.launch {
        repository.status.collect { st ->
            if (st.isRunning()) allowSnackbarOnFailure = true
            if (st is SyncEngineStatus.Failure && allowSnackbarOnFailure) {
                _effects.send(SyncEffect.ShowError(st.error))
            }
            if (!st.isRunning()) allowSnackbarOnFailure = false
        }
    }
}
```

The `SyncButton` composable (in `feature/sync/`) uses `animateFloatAsState` for rotation and `derivedStateOf` for the label. Long-press shows `AlertDialog` with full state text and a "Copy" button (Orgzly pattern).

#### `ConnectionResult` sealed for "Test connection"

```kotlin
sealed interface ConnectionResult {
    data object Idle : ConnectionResult
    data object InProgress : ConnectionResult
    data class Success(val version: String? = null) : ConnectionResult
    data class Error(val message: String) : ConnectionResult
}
```

#### `SyncFormEvent` for the config form

```kotlin
sealed interface SyncFormEvent {
    data object Saved : SyncFormEvent
    data object AlreadyExists : SyncFormEvent
    data object TestConnection : SyncFormEvent
    data class Error(val cause: Throwable) : SyncFormEvent
}
```

## Architecture (updated)

```
Repository.update(task)
  → syncRepository.enqueue(task)
      → SyncEngine.enqueue → HlcFactory → outboxDao.insert

SyncRunner.startScheduledSync(interval) [when Session.SignedIn]
  → engine.syncOnce()
      → push(): Result<PushSummary>
      → pull(): Result<PullSummary>

UI: SyncViewModel observes repository.status + repository.lastPush + repository.lastPull
    SyncButton: status.isRunning() → animated rotation
    SyncConfigScreen: ConnectionResult inline + AppError for all errors
```

## Consequences

- **Positive**: UI can now observe sync state; `SyncRepository` gives a clean module boundary; `Result<T>` matches project conventions; Orgzly UX patterns adopted.
- **Positive**: `enqueue()` wiring in repositories becomes testable via `FakeSyncRepository`.
- **Negative**: `SyncEngine` now has two responsibilities (push/pull logic + `syncOnce()` composition) — mitigated by `SyncRunner` extracting the orchestration.
- **Negative**: New `SyncRepository` interface adds an indirection. Mitigated by `FakeSyncRepository` for VM tests.
- **Deferred**: Whether to add `AppError.Auth(code: Int, body: String?)` — use string interpolation for now.

## Alternatives considered

- **Custom `SyncError` sealed type**: rejected — project has `AppError` used in 13+ files; duplicating error hierarchies violates DRY and complicates error handling at call sites.
- **`SyncEngine` as public interface**: rejected — feature modules should not depend on engine internals; `SyncRepository` provides stable API surface.
- **`LiveData` for UI**: rejected — `StateFlow` is the project standard (see `singularity-todo-ui-event-vs-state`).
- **Per-repo `RepoType` enum (Orgzly pattern)**: not applicable — single Supabase backend, not multi-repo.

## Links

- `core/sync/SyncEngine.kt` — status exposed as `StateFlow`, `Result<T>` returns
- `core/sync/SyncRepository.kt` — new facade interface
- `core/sync/SyncRunner.kt` — new orchestration class
- `core/sync/SyncTrigger.kt` — new enum
- `core/sync/AutoSync.kt` — new trigger fan-out
- `core/sync/ConnectionResult.kt` — new sealed for UI
- `core/sync/SyncFormEvent.kt` — new sealed for form
- `core/error/AppError.kt` — single error type for sync
- Orgzly reference: `orgzly-android-revived/app/src/main/java/com/orgzly/android/sync/SyncState.kt`
