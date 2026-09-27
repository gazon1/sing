---
name: singularity-todo-sync
description: Sync architecture for the Singularity Todo KMP app: Hybrid Logical Clock (HLC), ConflictResolver, SyncOutbox, SyncableEntity contract, SupabaseSyncApiClient, and SyncEngine orchestration. Use when adding a new entity to sync, debugging sync conflicts, or understanding the offline-first CRDT-style merge strategy.
---

# Singularity TODO — Sync Architecture

## Overview

Offline-first sync using Hybrid Logical Clock (HLC) timestamps + a Room-backed outbox + conflict resolution on merge. Supabase is the backend (PostgREST + Auth).

```
UI/UseCase → SyncableEntity.mutate() → HLC timestamp → SyncOutbox.enqueue()
                                                              ↓
                                    SyncEngine.poll() ←── timer (60s)
                                                              ↓
                                    SyncApi.push() ──→ Supabase PostgREST
                                                              ↓
                                    SyncApi.pull() ←── Supabase PostgREST
                                                              ↓
                                    ConflictResolver.checksumEquals() → applyRemoteWins (LWW) → Room upsert
```

## Hybrid Logical Clock (HLC)

Located: `shared/src/commonMain/.../core/sync/Hlc.kt`, `HlcFactory.kt`.

HLC = physical clock + logical counter. Guarantees causal ordering without synchronized clocks.

```kotlin
interface HlcClock {
    fun tick(millis: Long, nodeId: String): HlcTimestamp
    fun parse(ts: String): HlcTimestamp
    fun now(): HlcTimestamp  // tick with current System.currentTimeMillis()
    fun compare(a: HlcTimestamp, b: HlcTimestamp): HlcComparison
}
```

**HlcFactory** (`HlcFactory.kt`) creates HLC timestamps using `nodeId = deviceId` from `SettingsRepository.userId`.

**Important**: `HlcFactory` is constructed with `runBlocking { settings.userId.first() }` — this is a performance issue. It should be replaced with a lazy `nodeId: String` passed at construction time.

## SyncableEntity contract

Every entity that syncs implements `SyncableEntity`:

```kotlin
interface SyncableEntity<K> {
    val id: K
    val hlc: String?           // serialized HlcTimestamp, null = never synced
    val syncStatus: SyncStatus
    val serverVersion: Long    // monotonic server-side version
    val syncError: String?
    val lastSyncedAt: Long?    // epoch millis
    val deviceId: String?     // originating device

    /** Returns a copy with a new HLC timestamp for the given mutation. */
    fun mutate(counter: Long, deviceId: String): SyncableEntity<K>
}
```

**Adding a new sync entity** requires:
1. Add the 6 sync columns (`hlc`, `syncStatus`, `serverVersion`, `syncError`, `lastSyncedAt`, `deviceId`) — or use `@Embedded SyncColumns` (R6 refactoring).
2. Implement `SyncableEntity` with `mutate()`.
3. Add to `SyncEngine`'s entity list.
4. Add a `SyncOutbox` DAO entry for the entity.
5. Add tests in `SyncProtocolTest` + `ConflictResolverTest`.

## SyncOutbox

Room-backed queue of pending mutations. Located: `core/sync/SyncOutbox.kt`.

```kotlin
class SyncOutbox(
    private val taskOutboxDao: TaskOutboxDao,
    private val noteOutboxDao: NoteOutboxDao,
    ...
) {
    suspend fun enqueue(entity: SyncableEntity<*>, operation: SyncOperation): Unit
    suspend fun dequeue(limit: Int): List<OutboxItem>
    suspend fun markPushed(id: String, serverVersion: Long, hlc: String)
    suspend fun markFailed(id: String, error: String)
}
```

`SyncOperation`: `CREATE`, `UPDATE`, `DELETE`.

## ConflictResolver

Located: `core/sync/ConflictResolver.kt`.

CRDT-inspired merge strategy: **Last-Writer-Wins (LWW) with HLC tiebreaker**.

```kotlin
class ConflictResolver {
    fun resolve(local: SyncableEntity<*>, remote: SyncableEntity<*>): SyncableEntity<*> {
        return when {
            remote.hlc > local.hlc -> remote   // remote is newer
            local.hlc > remote.hlc -> local    // local is newer
            remote.serverVersion > local.serverVersion -> remote  // tiebreak by version
            else -> local
        }
    }
}
```

**For collection properties** (e.g., task tags): set union rather than LWW.

## SyncEngine orchestration

Located: `core/sync/SyncEngine.kt`.

Polls the outbox every 60s, pushes to Supabase, pulls remote changes, resolves conflicts:

```kotlin
class SyncEngine(
    private val outbox: SyncOutbox,
    private val api: SyncApiClient,
    private val conflictResolver: ConflictResolver,
    private val hlc: HlcFactory,
    private val settings: SettingsRepository,
) {
    suspend fun syncOnce() {
        pushPending()
        pullRemote()
    }

    private suspend fun pushPending() {
        val items = outbox.dequeue(50)
        for (item in items) {
            val result = api.push(item)
            outbox.markPushed(item.id, result.serverVersion, result.hlc)
        }
    }

    private suspend fun pullRemote() {
        val remote = api.pull(since = lastSyncedAt)
        for (remoteEntity in remote) {
            val local = dao.findById(remoteEntity.id)
            val merged = conflictResolver.resolve(local, remoteEntity)
            dao.upsert(merged)
        }
    }
}
```

## SyncApiClient (Supabase)

Located: `Modules.kt` as `SupabaseSyncApiClient`.

Uses Supabase PostgREST (not realtime subscriptions — only REST push/pull for now).

```kotlin
interface SyncApiClient {
    suspend fun push(item: OutboxItem): PushResult
    suspend fun pull(since: Long?): List<SyncedEntity>
}

data class PushResult(
    val serverVersion: Long,
    val hlc: String,
    val accepted: Boolean,
)
```

## Sync columns as @Embedded (R6)

Use the `@Embedded SyncColumns` pattern to avoid duplicating 6 fields across 4 entities:

```kotlin
data class SyncColumns(
    @ColumnInfo("server_version") val serverVersion: Long = 0L,
    @ColumnInfo("sync_status") val syncStatus: String = "LOCAL_ONLY",
    @ColumnInfo("sync_error") val syncError: String? = null,
    @ColumnInfo("last_synced_at") val lastSyncedAt: Long? = null,
    @ColumnInfo("device_id") val deviceId: String? = null,
    @ColumnInfo("hlc") val hlc: String? = null,
)

data class TaskEntity(
    @PrimaryKey val id: String,
    ...other fields...,
    @Embedded val sync: SyncColumns = SyncColumns(),
) : SyncableEntity<TaskId> {
    override val hlc: String? get() = sync.hlc
    // ... delegate all SyncableEntity props to sync
}
```

## Testing the sync pipeline

**TwoDeviceHarness** pattern (not yet implemented — create as a skill task):

```kotlin
class TwoDeviceHarness {
    private val engineA = SyncEngine(FakeOutbox(), FakeApiClient(), ConflictResolver(), HlcFactory("device-A"))
    private val engineB = SyncEngine(FakeOutbox(), FakeApiClient(), ConflictResolver(), HlcFactory("device-B"))
    private val sharedApi = FakeSyncApiClient()  // passes items A→B and B→A

    suspend fun simulateCreateThenSync() {
        engineA.create(Task(...))   // enqueue to A's outbox
        engineA.syncOnce()           // push to sharedApi
        engineB.syncOnce()           // pull from sharedApi
        assertEquals(engineA.state, engineB.state)  // eventual consistency
    }
}
```

## Key Files

| File | Purpose |
|---|---|
| `core/sync/Hlc.kt` | HLC timestamp, tick, compare |
| `core/sync/HlcFactory.kt` | Factory creating HLC timestamps per device |
| `core/sync/ConflictResolver.kt` | LWW + HLC tiebreaker |
| `core/sync/SyncOutbox.kt` | Room-backed outbox queue |
| `core/sync/SyncEngine.kt` | Orchestration: push → pull → merge |
| `core/sync/SyncApi.kt` | Supabase REST interface |
| `core/database/Entities.kt` | TaskEntity, NoteEntity, ProjectEntity, TagEntity (with sync columns) |
| `shared/src/jvmTest/.../core/sync/HlcTest.kt` | HLC unit tests |
| `shared/src/jvmTest/.../core/sync/ConflictResolverTest.kt` | Conflict resolution tests |
| `shared/src/jvmTest/.../core/sync/SyncProtocolTest.kt` | End-to-end sync protocol tests |
