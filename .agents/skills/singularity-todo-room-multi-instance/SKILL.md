---
name: singularity-todo-room-multi-instance
description: Cross-process SQLite access pattern for Singularity Todo KMP. Use when the same Room database is opened by multiple processes (Android app + MCP-server JVM CLI). Covers enableMultiInstanceInvalidation(), busy_timeout, PRAGMA wal_checkpoint(PASSIVE) (never TRUNCATE), polling-based Flow fallback for live UI updates, and the SqlDriverFactory setup.
---

# Cross-Process SQLite — Room Multi-Instance Pattern

## Problem

The Android app and the MCP-server JVM CLI both open the same SQLite file (`~/.singularity-todo/singularity-todo.db` on desktop, `todo.db` on Android). When the CLI writes a task, the Android UI does not automatically see it — Room's in-memory caches are stale.

This skill covers how to make the Android UI refresh automatically when the CLI writes.

## Root Cause

SQLite supports concurrent readers and **single writer**. When one process writes:
1. The writer holds a `RESERVED` or `EXCLUSIVE` lock.
2. Other readers see committed data **if** they are in the same transaction.
3. Room's `InvalidationTracker` uses SQLite hooks (`sqlite3_update_hook` / `sqlite3_commit_hook`) to detect changes from **the same connection**. Cross-process changes from a **different** process require additional setup.

## Architecture

```
┌─────────────────────────┐    ┌─────────────────────────┐
│      Android App        │    │    :mcp-server JVM CLI    │
│                          │    │                          │
│  RoomDatabase           │    │  SqlDriverFactory        │
│    └─ enableMulti...   │    │    └─ newConnection()    │
│    └─ InvalidationTracker│    │    └─ PRAGMA busy_timeout│
│         └─ polling Flow │    │    └─ PRAGMA journal_mode │
└─────────────────────────┘    └─────────────────────────┘
            │                              │
            │    SQLite WAL                 │
            └──────────┬───────────────────┘
                       ▼
          ~/.singularity/todo.db
```

## When to Use This Skill

- Adding a new JVM process (MCP-server, CLI) that reads/writes the shared SQLite file.
- Debugging "UI doesn't update after CLI write".
- Configuring `enableMultiInstanceInvalidation()` on Android.
- Setting `busy_timeout` and WAL mode for cross-process safety.
- Choosing between invalidation hooks and polling fallback.

Skip for: single-process scenarios, read-only CLI, or when using a server-mode database (Dolt, PostgreSQL).

## Step 1 — Android: Enable Multi-Instance Invalidation

In the Android-specific Room builder:

```kotlin
// shared/src/androidMain/.../core/di/PlatformModule.android.kt
Room.databaseBuilder<AppDatabase>(name = dbPath)
    .setDriver(BundledSQLiteDriver())
    .setSQLiteDatabaseConfigurationParameters(
        openInMemory = false,
        journalMode = OpenHelper.JOURNAL_MODE_WRITE_AHEAD_LOGGING,
    )
    .enableMultiInstanceInvalidation()   // ← CRITICAL for cross-process
    .build()
```

**What `enableMultiInstanceInvalidation()` does:**
- Room registers a custom `SQLiteOpenHelper` callback.
- On Android, Room uses a `ContentObservable` + broadcast `ACTION_DB_UPDATED` intent (for `ContentProvider`-based invalidation) OR a `RoomDatabase.Callback` with cross-process hooks.
- For WAL mode, the `InvalidationTracker` wakes up when the WAL is checkpointed by the writer.

**Limitation:** Works between Room instances on **Android only**. For JVM CLI → Android, the JVM does not use the same SQLite driver. Use polling fallback (Step 3).

## Step 2 — JVM CLI: PRAGMA Settings

When the MCP-server opens the SQLite connection, set these PRAGMAs **before any query**:

```kotlin
// shared/src/jvmMain/.../core/database/contract/SqlDriverFactory.jvm.kt
// or in mcp-server's Main.kt before starting Koin:

fun configurePragmas(driver: SqlDriver) {
    // WAL mode — allows concurrent readers + single writer
    driver.execute(null, "PRAGMA journal_mode=WAL", 0)
    
    // Wait up to 5s for writer to finish (not TRUNCATE)
    driver.execute(null, "PRAGMA busy_timeout=5000", 0)
    
    // synchronous = NORMAL is safe for WAL (less disk I/O)
    driver.execute(null, "PRAGMA synchronous=NORMAL", 0)
    
    // Read uncommitted to see uncommitted writes from other connections
    driver.execute(null, "PRAGMA read_uncommitted=1", 0)
}

private fun openDatabase(path: String): SqlDriver {
    val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
    configurePragmas(driver)
    return driver
}
```

**Critical PRAGMAs:**

| PRAGMA | Value | Why |
|---|---|---|
| `journal_mode` | `WAL` | Allows concurrent readers while a writer holds a lock |
| `busy_timeout` | `5000` (ms) | Wait up to 5s instead of immediately returning SQLITE_BUSY |
| `synchronous` | `NORMAL` | Safe for WAL (FULL = extra fsync, OFF = risky) |
| `read_uncommitted` | `1` | Read uncommitted writes (useful during long transactions) |

**Never use:**
- `PRAGMA journal_mode=TRUNCATE` — resets WAL but **blocks all writers** during checkpoint
- `PRAGMA locking_mode=NORMAL` (default is EXCLUSIVE on some versions) — keep default
- `PRAGMA read_uncommitted=1` on Android if not needed — Android SQLite already handles this

## Step 3 — Android: Polling Fallback

For JVM CLI → Android, Room's invalidation hooks don't fire. Use a `conflate()`d Flow with a polling interval:

```kotlin
// In TasksViewModel or a shared PollingWatcher.kt
class PollingWatcher(
    private val taskDao: TaskDao,
    private val clock: Clock,
) {
    /** 
     * Watch tasks with polling fallback.
     * When the CLI writes, the Flow emits the updated list after up to [delayMs].
     */
    fun watchTasksPolling(userId: String, delayMs: Long = 500): Flow<List<Task>> {
        return taskDao.watchActive(userId)
            .conflate()                           // skip intermediate emissions
            .onEach { delay(delayMs) }            // poll interval
    }
}
```

**Why `conflate()`?** Skips intermediate emissions — if the UI is slow, we don't need to process every single write. Just the latest.

**Why `delay(500)`?** 500ms poll interval is a good balance between responsiveness and CPU overhead (~0.1% CPU at idle).

**Performance note:** Each poll is a `SELECT COUNT(*) FROM tasks WHERE is_deleted = 0` — very cheap on SQLite. For 1000 tasks, this takes <1ms.

## Step 4 — Shutdown: Passive WAL Checkpoint

When the MCP-server shuts down (SIGINT), flush the WAL so Android sees all writes:

```kotlin
// In mcp-server Main.kt shutdown hook:
Runtime.getRuntime().addShutdownHook(Thread {
    runBlocking {
        // PASSIVE — never TRUNCATE (blocks writers)
        driver.execute(null, "PRAGMA wal_checkpoint(PASSIVE)", 0)
    }
})
```

**`wal_checkpoint(PASSIVE)`** — attempts to checkpoint as many frames as possible without blocking other writers. Returns `(pages_written, pages_skipped, pages_held)`.

**`wal_checkpoint(TRUNCATE)`** — **forbidden**. It forces a checkpoint and truncates the WAL file, but **blocks all writers** until the checkpoint completes. On a busy Android app, this can cause a deadlock.

**`wal_checkpoint(RESTART)`** — like TRUNCATE but also restarts the WAL log. Same deadlock risk. Forbidden.

## Step 5 — Avoid Hot Reads on Android During CLI Write

When the CLI is actively writing, Android readers may see `SQLITE_BUSY`. Room handles this via `busy_timeout`, but if you implement a custom query:

```kotlin
// ❌ WRONG — no retry, immediate SQLITE_BUSY on writer contention
val result = db.query("SELECT * FROM tasks WHERE user_id = ?", userId)

// ✅ CORRECT — Room's JdbcSqliteDriver handles busy_timeout automatically
// Just use Room queries. Never raw query() on the shared DB from JVM.
```

## Step 6 — SQLite Version Compatibility

`enableMultiInstanceInvalidation()` requires **SQLite ≥ 3.38.0** (Android API 21+ uses bundled SQLite 3.38.2+). Check:

```kotlin
// In AppDatabase onCreate:
val sqliteVersion = db.query("SELECT sqlite_version()", emptyList()) {
    it.getString(0)
}.single()
// Assert: sqliteVersion >= "3.38.0"
```

For older SQLite (pre-3.38), the polling fallback is the **only** reliable mechanism.

## Common Mistakes

```kotlin
// ❌ WRONG — TRUNCATE blocks writers
driver.execute("PRAGMA wal_checkpoint(TRUNCATE)")

// ✅ CORRECT
driver.execute("PRAGMA wal_checkpoint(PASSIVE)")

// ❌ WRONG — missing busy_timeout, immediate SQLITE_BUSY
driver.execute("PRAGMA journal_mode=WAL")

// ✅ CORRECT — wait 5s for writer
driver.execute("PRAGMA busy_timeout=5000")

// ❌ WRONG — polling without conflate, floods the coroutine scope
.onEach { delay(500) }

// ✅ CORRECT — conflate skips intermediate, only latest matters
.conflate().onEach { delay(500) }

// ❌ WRONG — enabling multi-instance without WAL
.enableMultiInstanceInvalidation()
// plus .setDriver(BundledSQLiteDriver()) // Bundled already uses WAL on Android

// ✅ CORRECT — WAL + multi-instance together
.setSQLiteDatabaseConfigurationParameters(
    journalMode = OpenHelper.JOURNAL_MODE_WRITE_AHEAD_LOGGING,
)
.enableMultiInstanceInvalidation()

// ❌ WRONG — raw query on shared SQLite from JVM
val stmt = connection.prepareStatement("SELECT * FROM tasks")
val rs = stmt.executeQuery()

// ✅ CORRECT — use Room's SqlDriver abstraction
val cursor = driver.executeQuery(null, "SELECT * FROM tasks", emptyList())
```

## Files Reference

| File | Role |
|---|---|
| `shared/src/androidMain/.../core/di/PlatformModule.android.kt` | `enableMultiInstanceInvalidation()` + WAL config |
| `shared/src/jvmMain/.../core/database/contract/SqlDriverFactory.jvm.kt` | `configurePragmas()` with WAL + busy_timeout |
| `mcp-server/src/main/kotlin/.../mcp/Main.kt` | `flushWalCheckpoint()` in shutdown hook |
| `core/observability/` | `UsageRecorder` + `RoomUsageRecorder` (polling helpers — deferred) |

## Related Skills

- `singularity-todo-mcp-server` — how the JVM CLI uses this infrastructure.
- `singularity-todo-cli-tool-surface` — write tools trigger the need for cross-process sync.
- `singularity-todo-room-migration` — Room setup patterns (this skill is about *runtime* cross-process, not migrations).
- ADR `2026-09-07-dogfooding-mcp-server` — concurrency strategy and rationale for WAL over TRUNCATE.
