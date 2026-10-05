---
title: Sync state is keyed by (owner, profile), and the cursor is only half of it
date: 2026-10-04
status: accepted
---

# Sync state is keyed by (owner, profile), and the cursor is only half of it

## Context

The sync core kept one download cursor for the whole app, in
`user_settings` under `sync/last_lsn`, with the same for
`last_successful_sync_at` and the auto-sync preferences. That is correct while
there is exactly one account and one profile on the device, and the feature has
always been in that state — which is why nothing was broken and nothing was
reported.

It stops being correct the moment a second scope exists, in a way that is
invisible from the inside:

- The cursor is a position in **a server's event log, and that log is per
  `(owner, profile)`**. A second profile, or a second account on a device that
  already synced the first, resumes from a position belonging to a different
  data set.
- Everything after that point is applied against the wrong history, and nothing
  reports it. A pull that applies a hundred events successfully looks *exactly*
  like a pull that started in the right place.
- `deviceId` makes it worse in the other direction. Regenerating it makes the
  server treat the next sync as a brand-new client, which is exactly what a user
  sees as "my other device stopped uploading".

Meanwhile the data itself was already scoped correctly: every entity carries a
`user_id`, and `assertCanWrite` runs before every write. The cursor was the one
piece of sync state that had never been through that machinery.

## Decision

Move sync state into the database as `sync_state`, primary key
`(owner_id, profile_id)`, holding the cursor, the last successful sync time, the
device id, and the sync preferences.

Three sub-decisions worth stating separately:

**The key is a pair, not a concatenated string.** `SyncScope(ownerId, profileId)`
is a value class, and the table has two columns rather than one key string. A
concatenation would work and would be unreadable in `sqlite3` at 3 a.m., and a
mistake in one half would be indistinguishable from a cache miss.

**Reads never fail.** `SyncStateRepository.get()` returns a default row for a
scope that has never synced, not null. First-run is the normal state; a null
would push a branch into every caller for a case that is not exceptional.

**The `SyncScopeProvider` is a port, not a direct dependency.** Answering "which
scope am I?" needs both the auth session and the active profile, and `core` does
not depend on `feature/profile`. The interface lives in `core/sync`; the binding
(`AuthProfileSyncScopeProvider`) lives in `core/di/Modules.kt`, which is the one
place that already knows about both. The alternative — passing two ids in from
every sync entry point — is how they drift apart.

## Rationale

The tempting cheaper fix is a key prefix: `sync/last_lsn:$profileId` in the same
DataStore. It is a few lines instead of a migration.

It is wrong for a reason that only shows up later: the writes would still be
non-transactional with the entity writes they describe, and a crash between
"applied event 40" and "stored cursor 40" is the duplicate-application case the
cursor is supposed to prevent. Moving the row into the same database as the
entities puts the cursor update in the same transaction boundary as everything
else it is a statement about. That is the reason for the table, and the composite
key is a consequence of it rather than the motivation.

On the legacy values: they are adopted at runtime, once, by the first scope to
initialise, and not by the migration. Which scope they belonged to is recorded
nowhere, so there is no correct owner to migrate them to — copying them into
whichever scope opens first would attribute one scope's cursor to another, which
is the exact failure the table exists to prevent. Adoption costs nothing when
wrong (the losing scope is one that has not synced yet, so its cursor is zero
anyway) and saves a full re-download when right. `deviceId` is generated rather
than adopted, because the old one lived in the session store and inventing a
second source of truth for it would be worse than a fresh one.

## Consequences

- `SyncPrefs` is now read-only in practice. Its KDoc says so, names the one
  remaining production read, and states the condition for deleting the file — a
  legacy type whose only remaining purpose is a migration window is invisible
  debt otherwise.
- The sync settings screen observes the current scope instead of reading a flat
  preference and keeping a copy. Its initial frame carries neutral defaults, so
  `autoSyncEnabled` defaults to `false`: a frame that claims auto-sync is on
  before anything has said so shows the user the wrong switch.
- A settings change with no scope is dropped, not queued. A deferred toggle would
  be applied later to whichever profile appeared next — a profile the user never
  touched.
- `SyncEngine.pull` takes the scope as a parameter rather than reading the
  current one, so the value it writes is the value it read. Re-reading inside
  would let a profile switch between the read and the write store one scope's
  position under another.
- `SyncOutcome.Skipped` now also means "no scope" — signed out, or no profile.
  That is a different condition from the one it was written for, and it is
  visible to callers as an outcome rather than as a silent no-op.
- `ScopedWriteQueryIsolationTest` learned that `owner_id` scopes a write just as
  `user_id` does. Without that, every scoped `sync_state` write was reported as
  a hole, and the two ways to silence that were both dishonest: allowlisting a
  correctly scoped query claims in writing that it is cross-profile, and renaming
  the column couples a schema decision to a lint rule.
- `SCHEMA_VERSION` is now a named constant referenced by the `@Database`
  annotation. Two migration tests had the literal `33` written as "the version
  the chain ends at", so every migration broke both, and the fix is an unrelated
  edit in two files that gets made carelessly or not at all.
- `SyncBootstrapperDispatchTest` pins which `DocType`s have a pull handler. The
  pull loop now stops at an event it cannot apply, which converts a missing
  handler from silent data loss into a permanent stall — so the dispatch table
  became load-bearing and is now asserted rather than assumed. `TimeEntry` is
  the known gap, pinned with the reason.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncState.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncStateRepository.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/di/AuthProfileSyncScopeProvider.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/database/Migration33To34.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/SyncStateMigrationTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/SyncBootstrapperDispatchTest.kt`
- `openspec/changes/supabase-auth-and-sync/specs/offline-sync/spec.md` (REQ-OS-009)
