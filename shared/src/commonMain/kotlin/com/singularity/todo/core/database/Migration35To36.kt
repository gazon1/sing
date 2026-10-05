package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v35 to v36 — the seed marker.
 *
 * Adds `sync_state.seed_completed`, which records that a scope's pre-existing local
 * data has already been queued for upload (REQ-OS-013).
 *
 * ## Why the new column defaults to false
 *
 * `false` means "this scope's data has not been seeded", which is the truth for
 * every existing row: nothing has been uploaded on their behalf, and the first
 * sync after the upgrade will seed them. Defaulting to `true` would read as
 * "already done" and silently skip the upload of everything the user created
 * before signing in — the exact data the requirement is about, lost to a default
 * value.
 */
class Migration35To36 : AutoMigrationSpec
