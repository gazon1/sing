---
title: "Per-connection PRAGMA enforcement and FK constraints"
date: 2026-10-02
tags: [tech-debt, database, room, kmp]
status: accepted
---

# Per-connection PRAGMA enforcement and FK constraints

## Context

`PlatformPragmas.applyTo(driver, path)` was called from `AppDatabaseFactory.build()` **before** `Room.databaseBuilder`. It opened an ephemeral connection, applied 4 PRAGMA statements, and immediately closed it:

```kotlin
fun applyTo(driver: SQLiteDriver, path: String) {
    val conn = driver.open(path)
    conn.use { conn ->
        for (sql in Commands) { conn.execSQL(sql) }
    }
}
```

SQLite PRAGMA semantics are split:

| PRAGMA | Scope | Effect of the old code |
|--------|-------|------------------------|
| `journal_mode = WAL` | **File-level** — stored in DB header | ✅ Worked (persists after connection closes) |
| `synchronous = NORMAL` | Per-connection | ❌ Applied to throwaway connection, lost |
| `cache_size = -2000` | Per-connection | ❌ Same |
| `temp_store = MEMORY` | Per-connection | ❌ Same |
| `PRAGMA foreign_keys = ON` | Per-connection | ❌ Same — FK enforcement was **off** |
| `busy_timeout = 5000` | Per-connection | ❌ Not set at all |

Additionally, `PRAGMA foreign_keys` defaults to `OFF` in SQLite. The schema had no `@ForeignKey` declarations either, so referential integrity was neither enforced by Room nor by SQLite.

## Decision

1. **Split PRAGMAs by scope.** `FileLevelCommands` (WAL only) keep using `applyOnceToFile()`. `PerConnectionCommands` (all others) move into `WrappingDriver` — a `SQLiteDriver` wrapper that applies them on every `open()` call.

2. **Use `setForeignKeyConstraintsEnabled(true)`** on the Room builder as the primary FK enforcement mechanism. This is the Room-3-KMP blessed API.

3. **Add `DatabaseCallback`** as a belt-and-suspenders measure — it applies `PerConnectionCommands` on every `onOpen` call, covering any connections Room opens internally before the wrapping driver is in place.

4. **Add `busy_timeout = 5000`** — not set previously, needed because multiple Koin `single`-bound repositories write to the DB concurrently. Without a busy timeout, SQLITE_BUSY returns immediately.

5. **No `@ForeignKey` added yet** — that is MR-12a (FK schema migration). MR-0 fixes the connection-level infrastructure; MR-12a will add the first FK constraint using this working per-connection enforcement.

## Consequences

- All per-connection PRAGMAs are now correctly applied to every connection Room acquires.
- FK enforcement is enabled both via `setForeignKeyConstraintsEnabled(true)` (Room-level) and `PRAGMA foreign_keys = ON` (SQLite-level).
- `WrappingDriver` is injected into Room via `setDriver()`, so every DAO operation uses a properly configured connection.
- The `applyOnceToFile()` call is still correct for `journal_mode=WAL`.
- The old `applyTo()` method is removed; `PlatformPragmas.Commands` is replaced by `FileLevelCommands` + `PerConnectionCommands`.

## Files changed

- `core/database/contract/PlatformPragmas.kt` — split commands, new `WrappingDriver`, `DatabaseCallback`, removed old `applyTo()`
- `core/database/AppDatabaseFactory.kt` — use `WrappingDriver`, call `applyOnceToFile()`, add `setForeignKeyConstraintsEnabled(true)`
