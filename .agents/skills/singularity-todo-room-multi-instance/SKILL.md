---
name: singularity-todo-room-multi-instance
description: Cross-process SQLite access for Singularity Todo KMP. Use when the same Room database is opened by more than one process (Desktop GUI + :mcp-server + the fire-reminder launcher). Covers the shared WAL database path, where PRAGMAs actually live (PlatformPragmas, not a hand-rolled configurePragmas), why there is no polling fallback and no wal_checkpoint on shutdown, and why the at(1)-style external mutators are not an option.
---

# Cross-Process SQLite — what is actually true

## Read this before copying anything

This skill was wrong for a long time and prescribed code that does not exist in this
repository: a hand-rolled `configurePragmas(driver)`, a JDBC driver, a
`enableMultiInstanceInvalidation()` call, a polling watcher class, and a
`flushWalCheckpoint()` in the MCP server's shutdown hook. None of those symbols are in the codebase. Every one of them
would have compiled if you had believed the file, and every one of them would have been
wrong in a way that only shows up under concurrency.

If you are extending this, read the source files named in **Where the truth lives** and
verify against them. If a claim here and the code disagree, the code wins and this file is
a bug.

## Who actually opens the database

Three processes, one file:

| Process | Opens it because |
|---|---|
| Desktop GUI (`desktopApp/main.kt`) | It is the app |
| `:mcp-server` (`Main.kt`) | An agent needs the same data |
| **`singularity-todo fire-reminder <id> <userId>`** | A `systemd --user` reminder unit fired; see ADR `2026-10-07-desktop-reminders-systemd-user-timers` |

All three resolve the **same** path — `~/.singularity-todo/singularity-todo.db` — from
`PlatformModule.jvm.kt`:

```kotlin
val dbPath = System.getProperty("user.home") + "/.singularity-todo/singularity-todo.db"
```

`mcp-server/src/main/kotlin/.../mcp/Main.kt` deliberately does **not** take a path
argument: Desktop, Android and MCP sharing one file is the design, not an accident.

The third row is the reason this skill matters more than it used to. Before WS4 there were
two processes, one long-lived and one agent-driven. Now a process can be spawned by the OS
at an arbitrary moment, hold the database open for the length of one notification, and exit.
Anything you change here has to be safe for that shape too.

## What actually makes it safe

Four things, all already in place. None of them is a JDBC configuration.

**1. WAL, set once at file level.** `journal_mode = WAL` is written to the database file
header and persists. It lives in `PlatformPragmas.FileLevelCommands`, applied by
`PlatformPragmas.applyOnceToFile` from `AppDatabaseFactory.build` before Room opens the
file. WAL is what allows concurrent readers alongside a single writer — the entire reason
a short-lived fire process can read while the GUI writes.

**2. Per-connection PRAGMAs, re-applied on every connection.**
`PlatformPragmas.PerConnectionCommands`:

```kotlin
"PRAGMA synchronous = NORMAL",   // fsync cost: O(1) vs FULL
"PRAGMA cache_size = -2000",     // 2 MiB page cache
"PRAGMA temp_store = MEMORY",
"PRAGMA foreign_keys = ON",
"PRAGMA busy_timeout = 5000",    // 5 s before returning SQLITE_BUSY
```

`busy_timeout` is the load-bearing one: without it a reader that arrives mid-write gets
`SQLITE_BUSY` immediately, and the write looks like corruption.

**These are applied by `WrappingDriver.open`**, which intercepts *every* connection Room
pulls from its internal pool. This is the part the old version of this skill got exactly
backwards: pragmas are not set once on "the driver". A `SQLiteDriver` is a factory, not a
connection, and Room may open several. Setting pragmas once and calling it configured is
how you end up with a pooled connection that never saw `busy_timeout`.

**3. One driver, not two.** `createSqlDriver()` returns the bundled native driver (`androidx.sqlite.driver.bundled`) on **both**
platforms — the same native SQLite. The older JDBC-based database class that needed
`org.xerial:sqlite-jdbc` is gone. If you are about to write
`driver.execute(null, "PRAGMA …", 0)`, you are in the wrong era: the API is
`connection.execSQL(...)`.

**4. Room's own invalidation.** Each process has its own `InvalidationTracker`, and Room
re-runs observers when it sees committed changes.

## What is deliberately NOT here

Three things this skill used to prescribe, and why each is wrong now.

**No polling fallback.** `PollingWatcher`, `watchActive`-with-a-delay, `.onEach { delay(n) }`
— none of it exists, and adding it would be a regression. Room's invalidation already fires
within a process; a poller would sit next to it and turn every list screen into a periodic
query. `TaskDao.watchActive` exists and is a plain Room `Flow` query, not a polling helper.

**No `wal_checkpoint` on shutdown.** The old Step 4 told you to flush the WAL from the MCP
server's shutdown hook. There is no such hook, and adding one would be actively wrong:
The TRUNCATE and RESTART checkpoint modes block other writers until they complete, so an
agent exiting while the GUI holds the database is exactly the moment to *not* checkpoint.
`PASSIVE` is safe but unnecessary — WAL readers see committed frames without a checkpoint,
that is what the journal is for.

**No `read_uncommitted`.** The old skill recommended `PRAGMA read_uncommitted=1`. Do not.
It reads another connection's uncommitted work, which is how you show a user a task that
another connection then rolls back. The correct answer to "I want to see the writer's data"
is `busy_timeout`, and then to wait for the commit.

## Adding a fourth process

If you add one, the checklist is short because the database side is done:

1. Resolve the path from the **same** expression. Do not accept a path argument; two paths
   means two databases and a class of bug that looks like data loss.
2. `startKoin { modules(listOf(platformModule(), coreLoggingModule()) + domainModule()) }`
   — `mcp-server/Main.kt:95` and `desktopApp/main.kt` both do this, and both note in KDoc
   that a hand-rolled subset drifts and leaves DAOs unbound, which kills the entry point at
   startup.
3. Resolve the active profile **before** any repository call. `ReminderRepository.get` is
   scoped to the active profile: fire a reminder before the profile resolves and you get
   `null` for a row that exists.
4. Short-lived is fine. `fireReminder` blocks, posts one notification, calls `stopKoin()`,
   exits. You do not need to checkpoint, drain, or reconcile anything on the way out.

## Why there is no external job mutator

The temptation in this area is always the same: to cancel or list things from outside the
process. Do not shell out to a system job daemon. The deleted `at(1)` backend ran `atq` and
`atrm` across **every** job on the host — including jobs the user had queued outside this
app — and `AtBackendConfinedTest` now fails the build if anything reaches for `at`, `atq`
or `atrm` again.

`systemd --user` is the replacement because every unit has a name this app chose, so
cancelling is a targeted `systemctl stop <name>` and never an enumeration.

## Where the truth lives

| File | What it actually does |
|---|---|
| `shared/src/commonMain/.../core/database/contract/PlatformPragmas.kt` | Every PRAGMA in the project |
| `shared/src/commonMain/.../core/database/AppDatabaseFactory.kt` | `build()` — applies file-level pragmas, then Room |
| `shared/src/jvmMain/.../core/database/contract/WrappingDriver.jvm.kt` | Re-applies per-connection pragmas on **every** `open` |
| `shared/src/jvmMain/.../core/database/contract/SqlDriverFactory.jvm.kt` | `createSqlDriver()` — the bundled native driver (`androidx.sqlite.driver.bundled`), same as Android |
| `shared/src/jvmMain/.../core/di/PlatformModule.jvm.kt` | The shared DB path |
| `shared/src/jvmTest/.../arch/AtBackendConfinedTest.kt` | Fails if `at(1)` comes back |

## Related

- `singularity-todo-room-migration` — schema migrations. Different concern; this file is
  about **runtime** concurrency.
- `singularity-todo-mcp-server` — the agent-side entry point that opens the same file.
- ADR `2026-10-07-desktop-reminders-systemd-user-timers` — why a second process appeared.
- ADR `2026-10-06-notification-port-deleted-because-it-cancelled-other-peoples-jobs` — why
  there is no job daemon to shell out to.