---
title: ADR: Sync Pull Application & Consumer Wiring
date: 2026-09-23
status: accepted
description: How pull events are applied to local entities, how handlers are registered, and how repositories enqueue sync changes.
---

# ADR: Sync Pull Application & Consumer Wiring

## Context

The sync engine has a working push (outbox) and a stub pull. Pull requires:
1. An `EntityApply` handler for each `DocType` (Task, Note, Project, Tag)
2. A `ConflictResolver` to merge server events with local changes
3. Consumers (repositories) must call `syncRepository.enqueue(entity)` on local mutations

The existing domain models (`Task`, `Note`, `Project`, `Tag`) do not implement `SyncableEntity`.

## Decision

### 1. SyncableEntity on domain models

Domain models gain optional sync fields with sensible defaults. This keeps sync as a transparent layer rather than a separate object graph:

```kotlin
// Task.kt
data class Task(
    // ... existing fields ...
    val serverVersion: Long = 0,   // HLC epoch millis; 0 = local-only
    val hlc: Hlc? = null,          // Hybrid Logical Clock timestamp for causal ordering
) : SyncableEntity {
    override val id: String get() = id.value
    override val docType: DocType get() = DocType.Task
    override fun toJson(): JsonObject = StableJson.decodeFromJsonElement(...)
}
```

`Note`, `Project`, `Tag` follow the same pattern. `serverVersion = 0` and `hlc = null` are safe defaults: locally-created entities have no server version until they are synced, at which point `SyncEngine` stamps them via the outbox.

The `SyncableEntity` interface lives in `core/sync/` — the same module as `SyncEngine`, which already depends on domain models (via repository interfaces). This is a known module dependency direction violation (domain → infra), accepted as a necessary tradeoff for the sync marker to live close to the sync engine.

### 2. EntityApply handlers

Each feature module registers its pull handler at app startup via a Koin `onStart` callback or an `init {}` block in the module:

```kotlin
// TasksDiModule.kt
fun tasksModule() = module {
    // ... existing bindings ...

    // Register Task pull handler with SyncEngine.
    // Runs after all other modules are loaded; lazy to avoid circular DI.
    onStart {
        val engine: SyncEngine = get()
        engine.registerHandler(DocType.Task) { event ->
            val taskRepo: TaskRepository = get()
            ApplyOutcome.Applied  // stub — see §4
        }
    }
}
```

Handlers are **not** injected directly into `SyncEngine`. Instead, `SyncEngine` owns a `MutableStateFlow<Map<DocType, EntityApply>>` and provides `registerHandler(docType, apply)`. This avoids circular DI dependencies (infra module would need feature modules).

### 3. ConflictResolver

Server events may conflict with local changes (same entity modified on two devices). The resolver uses Last-Write-Wins (LWW) based on `Hlc`:

```kotlin
// ConflictResolver.kt (commonMain)
fun interface ConflictResolver {
    suspend fun resolve(local: SyncableEntity, remote: SyncableEntity): ApplyOutcome
}

class HlcConflictResolver : ConflictResolver {
    override suspend fun resolve(local: SyncableEntity, remote: SyncableEntity): ApplyOutcome {
        return if ((remote.hlc?.epoch ?: 0L) >= (local.hlc?.epoch ?: 0L)) {
            ApplyOutcome.Applied  // remote wins — apply remote to local DB
        } else {
            ApplyOutcome.Conflict("local newer than remote")
        }
    }
}
```

`ApplyOutcome.Conflict` increments the conflict counter in `PullSummary` but does not overwrite local data. The UI layer (SyncViewModel) can surface conflicts to the user.

### 4. Pull flow (stub — Tier 4+)

The actual DB apply (`taskRepo.upsert(entity)` from a remote event) is deferred. The Tier 3 handler is a stub:

```kotlin
engine.registerHandler(DocType.Task) { event ->
    ApplyOutcome.Applied  // placeholder
}
```

This is intentional — full conflict resolution + remote entity application requires the full `SyncEvent` → domain model mapping and is Tier 4 work.

### 5. Consumer wiring (enqueue on mutation)

Each repository calls `syncRepository.enqueue(entity)` after a successful local mutation. The call is fire-and-forget from the caller's perspective — a failed enqueue does not roll back the local change.

```kotlin
// TaskRepositoryImpl.create()
override suspend fun create(item: Task): Result<Task> = runCatching {
    val inserted = item.copy(serverVersion = 0, hlc = null)
    taskDao.upsert(inserted.toEntity())
    _changes.tryEmit(inserted)
    // Enqueue AFTER the local write succeeds.
    // Failure is logged but does not fail the Result.
    syncRepository.enqueue(inserted).onFailure { log.e(it) { "enqueue failed" } }
    inserted
}
```

`SyncRepositoryImpl` wraps `SyncEngine.enqueue()`, which writes to the outbox and is a pure local operation (no network). It always returns `Result.success` unless the outbox write itself fails (should not happen).

### 6. SyncRepository injection

`SyncRepository` is added to repository constructors. Constructor signature for `TaskRepositoryImpl`:

```kotlin
class TaskRepositoryImpl(
    private val taskDao: TaskDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
    private val syncRepository: SyncRepository,   // NEW
)
```

Same pattern for `NoteRepositoryImpl`, `ProjectsRepositoryImpl`, `TagsRepositoryImpl`.

## Consequences

- Domain models gain `serverVersion` and `hlc` fields — existing call sites unaffected (defaults)
- `SyncableEntity.toJson()` uses `StableJson` — no new serialization surface
- `ConflictResolver` is a fun interface — can be injected separately if needed later
- Enqueue is best-effort — local changes are never rolled back due to sync failures
- `SyncEngine` and `SyncRunner` remain `internal` — feature modules never touch them directly

## Links

- `SyncEngine.kt` — `registerHandler`, `enqueue`, `pull()`
- `SyncableEntity.kt` — interface definition
- `TasksDiModule.kt`, `NotesDiModule.kt` — handler registration
- `TaskRepositoryImpl.kt`, `NoteRepositoryImpl.kt` — consumer wiring
