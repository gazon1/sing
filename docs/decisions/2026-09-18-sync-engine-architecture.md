---
title: "Sync engine: HLC + SyncEngine + ConflictResolver"
date: 2026-09-18
status: accepted
tags: [sync, architecture, core, hlc, conflict-resolution]
---

## Context

The app needs offline-first data synchronization with a Supabase backend. The main challenges are:
1. **Conflict resolution** — concurrent edits to the same entity from multiple devices
2. **Ordering** — causal ordering of events across distributed nodes
3. **Offline outbox** — mutations must survive app crashes before being sent to the server
4. **Polling push** — the client initiates all sync; the server does not push

## Decision

### HLC (Hybrid Logical Clock)

Every entity mutation is stamped with a `Hlc` timestamp (see `HlcFactory` and `Hlc`). HLC provides:
- **Causal ordering**: if event A happened-before event B, then `hlc(A) < hlc(B)`
- **Liveness**: `hlc.now()` always increases even without wall-clock synchronization
- **Portability**: pure Kotlin implementation, no native deps

`Hlc` is stored on every `SyncableEntity` as `hlc: String?` and written to the Room `SyncColumns` embedded.

### SyncEngine (push side)

`SyncEngine` is the orchestrator:
1. Every mutation calls `syncEngine.enqueue(entity)` — writes a `DeltaPatch` to the local outbox (Room `SyncOutboxDao`)
2. A coroutine job polls `push()` every **30 seconds** when signed in
3. `push()` batches all pending patches and sends `BatchPushRequest` to Supabase
4. The job is canceled automatically when session becomes `SignedOut` / `Anonymous`

```kotlin
// Enqueueing a mutation (in a UseCase or Repository)
suspend fun updateTask(task: Task): Result<Unit> {
    repository.update(task)
    syncEngine.enqueue(task)  // non-blocking, writes to outbox
}
```

### ConflictResolver (server-side, documented here for clarity)

The server uses `ConflictResolver.checksum(stateJson)` as a fast-reject heuristic:
1. Client sends `shadowChecksum` = checksum of the entity state it last saw
2. Server compares `shadowChecksum` against the current server state
3. If they match → no conflict, apply the patch
4. If they differ → server returns `isRetriable = true`; client must `pull()` the latest and retry

This is **not** a full CRDT. It handles the common case (single device, sequential edits) well. True concurrent edits require user resolution (future work).

### Pull (future)

`pull(sinceLsn)` fetches events from the server since a given LSN (log sequence number). The current implementation is a stub — it receives events but does not apply them. This is tracked as future work.

## Architecture

```
Repository.update(task)
  → syncEngine.enqueue(task)
      → HlcFactory.tick() → DeltaPatch
      → outboxDao.insert(SyncOutboxEntity)

SyncEngine.push() [every 30s when signed in]
  → outboxDao.getPending()
  → api.batchPush(BatchPushRequest)
  → outboxDao.delete(patchId) | markFailed
```

## Consequences

- **Positive**: Simple, predictable push model; HLC provides causal ordering; outbox is durable (Room)
- **Negative**: No server-side push; conflict resolution is last-write-wins with checksum fast-reject (not full CRDT)
- **Negative**: `pull()` is not yet implemented — remote changes do not appear on the device

## Links

- `core/sync/SyncEngine.kt` — push orchestrator
- `core/sync/HlcFactory.kt` / `Hlc.kt` — hybrid logical clock
- `core/sync/ConflictResolver.kt` — checksum-based conflict detection
- `core/sync/SyncOutboxDao.kt` — durable outbox (Room DAO)
