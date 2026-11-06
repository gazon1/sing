package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v33 to v34 — per-scope sync state.
 *
 * Adds the `sync_state` table, keyed by `(owner_id, profile_id)`, holding the download
 * cursor, the last successful sync time, the device id and the sync preferences.
 *
 * ## Why the old cursor did not simply move across
 *
 * The DataStore keys it replaces are one value for the whole app. Which scope they
 * belonged to is not recorded anywhere, so there is no correct owner to migrate them
 * to. Copying them into whichever scope happens to open first would attribute one
 * scope's cursor to another, which is the exact failure the table exists to prevent.
 *
 * So they are adopted at runtime instead, once, by the first scope to initialise —
 * see `RoomSyncStateRepository`. Losing a cursor costs one full re-download; getting it
 * wrong costs a pull that resumes inside another profile's history.
 */
class Migration33To34 : AutoMigrationSpec
