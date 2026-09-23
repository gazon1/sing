---
description: Real pull event handlers, repository upsert, sync UI (ViewModel/Button/Screen), and entity sync field round-tripping.
status: accepted
---

# ADR: Sync Pull Handlers & Sync UI

## Context

Tier 2 delivered the sync orchestration layer (`SyncEngine`, `SyncRunner`, `SyncRepository`, `DataStoreSyncPrefs`, `SyncScheduler`). Tier 3 requires:

1. **Real pull handlers** — stub handlers in `SyncBootstrapper` must actually apply remote events to local entities
2. **`upsert()` on repositories** — a method for remote-initiated upserts (distinct from `create`/`update` which emit change events)
3. **`SyncableEntity` on all entities** — `Note`, `Project`, `Tag` did not implement the interface
4. **Sync UI** — `SyncViewModel`, `SyncButton`, `SyncConfigScreen` for settings integration
5. **Sync column round-tripping** — `serverVersion`/`hlc` must survive `Entity → DB → Entity` without loss

## Decision

### 1. Pull handlers (SyncBootstrapper)

`SyncBootstrapper` is instantiated as a lazy singleton in `CoreDiModule` **after** all feature repositories are registered. Its `init {}` block calls `registerHandler` for each `DocType`. The handler function is a lambda that closes over the repository:

```kotlin
// SyncBootstrapper.kt
internal class SyncBootstrapper(
    private val engine: SyncEngine,
    private val taskRepo: TaskRepository,
    private val noteRepo: NotesRepository,
    private val projectRepo: ProjectsRepository,
    private val tagRepo: TagsRepository,
) {
    init { registerHandlers() }

    private fun registerHandlers() {
        engine.registerHandler(DocType.Task) { event ->
            handleEvent(event) { data: JsonObject ->
                val task = StableJson.decodeFromString<Task>(data.toString())
                taskRepo.upsert(task)
            }
        }
        // Note, Project, Tag follow the same pattern
    }

    private suspend fun handleEvent(
        event: SyncEvent,
        applyRemote: suspend (JsonObject) -> Unit,
    ): ApplyOutcome {
        return try {
            when (event.eventType) {
                SyncEventType.CREATED,
                SyncEventType.UPDATED,
                SyncEventType.RESTORED,
                -> {
                    val data = event.data
                    if (data == null || data == JsonNull) return ApplyOutcome.Applied
                    val obj = data as? JsonObject ?: return ApplyOutcome.Applied
                    applyRemote(obj)
                    ApplyOutcome.Applied
                }
                SyncEventType.DELETED -> {
                    // Soft-delete from remote not yet wired — Tier 4
                    ApplyOutcome.Applied
                }
            }
        } catch (e: Throwable) {
            ApplyOutcome.Conflict("Apply failed: ${e.message ?: e::class.simpleName}")
        }
    }
}
```

Key points:
- `handleEvent` is a shared helper that catches exceptions, checks `eventType`, and delegates to the typed `applyRemote` lambda
- Deleted events are currently skipped (soft-delete not wired to remote events — Tier 4)
- Failures return `ApplyOutcome.Conflict` (not `Applied`) so the pull summary reflects reality

### 2. `upsert()` on repositories

Each repository interface gains an `upsert(entity): Entity` method for pull handler use:

```kotlin
// TaskRepository.kt
interface TaskRepository : SoftDeletable<Task, TaskId> {
    // ... existing members ...
    /** For remote sync events — does NOT emit [_changes]. */
    suspend fun upsert(task: Task): Task
}
```

The method:
- Takes a fully-hydrated domain object (with `serverVersion` and `hlc` already set by the pull handler)
- Writes directly to the DAO without emitting `_changes` (the pull handler is not a user action — observers don't need to react)
- Returns the entity as-written (same reference for `Task`, constructed for `Note`/`Project`/`Tag`)

Implementation pattern in all four repositories:

```kotlin
// TaskRepositoryImpl.kt
override suspend fun upsert(task: Task): Task {
    taskDao.upsert(task.toEntity())
    return task
}
```

`TaskRepositoryImpl.create()` and `update()` now call `syncRepository.enqueue(item)` after the local write (best-effort, failure logged but not propagated).

### 3. Sync fields on all entities

All four domain models implement `SyncableEntity`:

```kotlin
// Note.kt
data class Note(
    val id: NoteId,
    // ... existing fields ...
    val serverVersion: Long = 0,
    val hlc: Hlc? = null,
) : SyncableEntity {
    override val syncId: String get() = id.value
    override val docType: DocType get() = DocType.Note
    override val syncServerVersion: Long get() = serverVersion
    override val syncHlc: Hlc? get() = hlc
    override fun toJson(): JsonObject {
        @Suppress("UNCHECKED_CAST")
        val ser = serializer<Note>()
        return StableJson.encodeToJsonElement(ser, this) as JsonObject
    }
}
```

`Project` and `Tag` follow the same pattern. `Task` already had these fields.

**`toJson()` fix**: Previously used double-serialization (`encodeToString → decodeFromString<JsonObject>`). Now uses `StableJson.encodeToJsonElement(serializer<T>(), this) as JsonObject` directly — eliminates the unnecessary round-trip.

### 4. Entity mapper round-tripping

All `toEntity()` mappers now include `SyncColumns`:

```kotlin
// TaskRepositoryImpl.kt
private fun Task.toEntity(): TaskEntity = TaskEntity(
    // ... existing fields ...
    sync = SyncColumns(serverVersion = serverVersion, hlc = hlc?.encoded),
)

// ProjectsRepositoryImpl.kt
internal fun Project.toEntity(): ProjectEntity = ProjectEntity(
    // ...
    sync = SyncColumns(serverVersion = serverVersion, hlc = hlc?.encoded),
)
```

All `*Entity.to*()` mappers (in `Mappers.kt` and inline in repository files) now include sync fields:

```kotlin
// Mappers.kt
internal fun TaskEntity.toTask(): Task = Task(
    // ...
    serverVersion = sync.serverVersion,
    hlc = sync.hlc?.let { Hlc(it) },
)
```

The `SyncColumns` `@Embedded` in each entity uses `hlc: String?` (encoded `Hlc` as a string). This means `Hlc` must be `@Serializable` — it already is.

### 5. SyncViewModel (MVI)

Three sealed interfaces in `SyncViewModel.kt`:

```kotlin
// Intent
sealed interface SyncIntent {
    data object SyncNow : SyncIntent
    data class SetAutoSync(val enabled: Boolean) : SyncIntent
    data class SetInterval(val minutes: Int) : SyncIntent
    data class AcknowledgeError(val error: AppError) : SyncIntent
}

// State
data class SyncState(
    val status: SyncEngineStatus = SyncEngineStatus.Idle,
    val autoSyncEnabled: Boolean = false,
    val intervalMinutes: Int = 30,
    val lastSyncedAt: Long? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

// Effect (one-shot)
sealed interface SyncEffect {
    data class ShowError(val message: String) : SyncEffect
    data object SyncCompleted : SyncEffect
}
```

Key design decisions:
- **`allowSnackbarOnFailure` debounce**: snackbar shown only after a sync cycle completes (not during). Set `true` when status transitions `Running → Idle`, reset to `false` when entering `Running`. Prevents spamming snackbars during long sync.
- Reads initial state from `SyncPrefs` synchronously in `buildState()` (called from constructor) — avoids async init flicker
- Collects `repository.status` and `repository.lastPull` as reactive streams
- `SetAutoSync(true)` calls `repository.startScheduledSync()` immediately; `SetAutoSync(false)` calls `stopScheduledSync()`

### 6. SyncButton composable

```kotlin
@Composable
fun SyncButton(
    status: SyncEngineStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sync_rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )
    // ...
    Icon(
        imageVector = when (status) {
            is SyncEngineStatus.Idle -> Icons.Default.CloudDone
            is SyncEngineStatus.Pushing,
            is SyncEngineStatus.Pulling -> Icons.Default.Refresh
            is SyncEngineStatus.NoConnection -> Icons.Default.CloudOff
            is SyncEngineStatus.Failure -> Icons.Default.SyncProblem
        },
        modifier = Modifier
            .size(24.dp)
            .then(if (status.isRunning()) Modifier.rotate(rotation) else Modifier),
    )
}
```

States:
- **Idle** → static cloud-done
- **Pushing/Pulling** → rotating refresh (continuous spin via `rememberInfiniteTransition`)
- **NoConnection** → static cloud-off (no animation, outline tint)
- **Failure** → static sync-problem (error tint)

### 7. SyncConfigScreen

A settings panel with:
- Status line + last-synced timestamp
- Auto-sync toggle (Switch) — starts/stops scheduled sync
- Interval slider (15/30/60/120 min) — visible only when auto-sync is on
- Manual "Sync now" button with `CircularProgressIndicator` during sync
- Inline error banner (dismissible)

Error messages from `SyncEffect.ShowError` are surfaced via `state.errorMessage` — a `String?` field that the ViewModel sets when an error occurs and clears on `AcknowledgeError`.

### 8. DI wiring

`SyncViewModel` is registered in `CoreDiModule` as a `viewModel`:

```kotlin
// CoreDiModule.kt
viewModel { SyncViewModel(get(), get(), get()) }
```

`SyncRepository` is injected into all four repository implementations (new constructor parameter). DI module updated:
- `TasksDiModule.kt`: `TaskRepositoryImpl(get(), get(), get(), get())` (added `SyncRepository`)
- `NotesDiModule.kt`: `RoomNotesRepository(get(), get(), get(), get())`
- `ProjectsDiModule.kt`: `ProjectsRepositoryImpl(get(), get(), get(), get())`
- `TagsDiModule.kt`: `TagsRepositoryImpl(get(), get(), get(), get())`

### 9. FakeRepositories (test compilation)

All four `Fake*Repository` classes in `test/fakes/FakeRepositories.kt` gained `upsert()` implementations:

```kotlin
override suspend fun upsert(task: Task): Task {
    store.upsert(task)
    return task
}
```

This is required for compilation — any test that constructs a repository now needs to satisfy the new interface method.

## Consequences

- Pull events are now actually applied to the local database (not stub)
- `serverVersion`/`hlc` survive the full round-trip: domain → entity → DAO → DB → entity → domain
- All four entity types can be synced (previously only `Task` had `SyncableEntity`)
- `SyncRepository` becomes a required dependency of all four repositories — circular DI risk monitored
- `SyncBootstrapper` remains `internal` — no feature code can bypass `SyncRepository`
- `SyncViewModel` is `ViewModel` (extends AndroidX `ViewModel`) — standard Koin `viewModel {}` DSL applies
- Pull handler for `DELETED` events is a stub — entities are not soft-deleted from remote events yet

## Consequences (negative)

- `SyncableEntity` in `core/sync/` while domain models are in `feature/*/domain/` — domain → infra dependency. Accepted tradeoff: sync is a cross-cutting concern and the interface lives near the engine.
- `Task.toJson()` uses `encodeToJsonElement` — this is `StableJson` which uses `encodeDefaults = true`, meaning default `serverVersion = 0` and `hlc = null` are always serialized. This is correct for the sync protocol (server needs to know the base version).
- `SyncButton` rotation animation uses `rememberInfiniteTransition` — the animation restarts on recomposition with a different `status`. Acceptable because `status` is a stable key.

## Links

- `SyncBootstrapper.kt` — handler registration and `handleEvent`
- `SyncViewModel.kt` — MVI (Intent / State / Effect)
- `SyncButton.kt` — animated composable
- `SyncConfigScreen.kt` — settings panel
- `TasksDiModule.kt`, `NotesDiModule.kt`, `ProjectsDiModule.kt`, `TagsDiModule.kt` — DI updates
- `Mappers.kt`, `TaskRepositoryImpl.kt`, `ProjectsRepositoryImpl.kt` — mapper fixes
- `FakeRepositories.kt` — `upsert()` implementations
