---
title: Calendar sync — Tasks.org patterns adoption
date: 2026-09-22
status: accepted
---

# Calendar sync — Tasks.org patterns adoption

## Context

The calendar sync feature (`calendar_sync`) performs a one-way sync: local `Task` → Android system calendar via `ContentResolver`. It was built in MR-4 with a simple `CalendarSyncWorkScheduler` that enqueues a `CalendarSyncWorker` directly.

After reviewing [Tasks.org CalDAV implementation](https://github.com/tasks/tasks/tree/main/kmp/src/commonMain/kotlin/org/tasks/caldav) (specifically the sync orchestrator, exception hierarchy, and debounce patterns), three high-value improvements were identified for our one-way sync:

1. **Debounced `SyncAdapters` pipeline** — coalesces rapid `TaskDirty` triggers (user edits 5 tasks in quick succession → single sync) and deduplicates redundant syncs via a dirty hash.
2. **`SyncSource` enum + `upgrade()`** — prioritises triggers: `Manual`/`ConfigChanged` beat `TaskDirty`/`Periodic`, and carries a `showIndicator` flag for the UI.
3. **Layered `SyncException` hierarchy + `translateExceptions`** — Android-specific exceptions (`SecurityException`, `IllegalArgumentException`, `SQLiteException`) are caught at the `CalendarProviderPort` boundary and re-thrown as typed `CalendarSyncException` subclasses. The worker maps them to `CalendarSyncStatus.Failed` with a `FailureType` for UI-tailored recovery actions.

Other Tasks.org patterns (cTag/ETag diff, VtodoCache, CalDAV HTTP auth, per-collection 3-way merge, `AutoCloseable` cleanup) are out of scope — they require either two-way sync or are minor leak fixes deferred to a future cleanup pass.

## Decision

### A. `SyncSource` enum + `CalendarSyncOrchestrator`

Introduce a `SyncSource` enum with five cases:

| Case | `showIndicator` | `immediate` |
|---|---|---|
| `Periodic` | false | false |
| `ConfigChanged` | true | true |
| `TaskDirty` | true | false |
| `Manual` | true | true |
| `AppResumed` | false | false |

`upgrade(other)` picks the strongest of two concurrent triggers: `showIndicator` takes priority, then `immediate`.

`CalendarSyncOrchestrator` is a Koin `single` that owns:
- `Channel<SyncSource>(UNLIMITED)` — incoming triggers (never blocks callers)
- `MutableStateFlow<SyncSource?>` — strongest pending source for UI indicator
- 1-second debounced collector that calls `scheduler.enqueueSync()` only when `DirtyHashProvider.hash()` differs from the last handoff

`DirtyHashProvider` computes a stable `Long` hash from: enabled flag, target calendar ID, app package, minute-granularity timestamp, and all active tasks' sync-relevant fields. Same hash = nothing meaningful changed = skip scheduling.

### B. Exception hierarchy

```
CalendarSyncException (sealed)
├── PermissionRevokedException   — READ/WRITE_CALENDAR revoked
├── CalendarNotFoundException    — calendar deleted from system
├── CalendarAppMissingException  — selected app uninstalled
├── TransientSyncException       — ContentResolver/SQLite error
└── NetworkSyncException          — I/O error
```

`translateExceptions(block)` wraps `ContentResolver` calls in Android `AndroidCalendarProvider`. Catches: `SecurityException`, `IllegalArgumentException` (with URI message), `IllegalStateException`, `SQLiteException`, `IOException`. JVM stub is a no-op passthrough.

### C. `CalendarSyncStatus.Failed` extension

`FailureType` enum: `PermissionRevoked`, `CalendarNotFound`, `CalendarAppMissing`, `Transient`, `Network`, `Unknown`.

`CalendarSyncStatus.Failed(reason, type)` carries the type. The worker maps each `CalendarSyncException` subtype to the corresponding `FailureType`. `PermissionRevoked` and `CalendarAppMissing` return `Result.success()` (not retryable — user must act); others return `Result.retry()`.

## Rationale

**Debounce + dirty hash**: Without it, a user editing 10 tasks rapidly would enqueue 10 WorkManager jobs. With it, at most 1 job fires after the 1-second quiet window, and only if something actually changed.

**`SyncSource.upgrade()`**: A `TaskDirty` trigger during a `Manual` sync should not be debounced into the background — the user explicitly asked for "sync now". `upgrade()` makes this explicit and testable.

**Exception hierarchy**: `ContentResolver` throws generic Android exceptions with no typed context. `translateExceptions` converts them at the boundary, giving the worker enough information to choose retry vs. user-action-required. Without it, every failure is `FailureType.Unknown`.

**Why not cTag/ETag**: Our sync is one-way. We have no server to hold a cTag. When we add two-way CalDAV, the VtodoCache snapshot pattern from Tasks.org will be directly applicable.

**Why not `AutoCloseable`**: Single-shot `ContentResolver` calls in `withContext(Dispatchers.IO)` don't hold resources beyond the call lifetime. The leak is negligible; proper cleanup requires refactoring `CalendarProviderPort` to a `suspend fun <T> query(uri, block: Cursor.() -> T)` style which is out of scope.

## Consequences

### Improved
- No more write storms from rapid task edits
- UI can show "Syncing…" indicator during `Manual`/`ConfigChanged` syncs
- Settings screen can show specific recovery actions per failure type

### Deferred
- cTag/ETag two-way diff (needs CalDAV server)
- VtodoCache local-edit guard (needs two-way sync)
- Per-collection 3-way merge (needs attachments/tags bidirectional)
- `AutoCloseable` ContentResolver cleanup

## Links

- Tasks.org `SyncAdapter` pipeline: `org.tasks.caldav.SyncAdapter` + `SyncAdapters.kt`
- Tasks.org `SyncException`: `org.tasks.caldav.SyncException` sealed hierarchy
- Tasks.org `SyncSource`: `org.tasks.caldav.SyncSource` enum
- Implementation: `shared/src/commonMain/kotlin/com/singularity/todo/feature/calendar_sync/sync/`
