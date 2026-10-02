package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v27 to v28 — creates the `ai_proposal` table.
 *
 * Proposals are local-only by design: they are a staging area for an unconfirmed
 * change, and a change that has not been confirmed should not exist on another
 * device. The `sync` columns are present so a future sync engine can opt in without
 * another schema migration, but nothing writes to `sync_outbox` for these rows.
 */
class Migration27To28 : AutoMigrationSpec
