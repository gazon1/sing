package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v15 to v16 — adds `saved_searches` table for saved search persistence.
 *
 * Schema:
 * - id TEXT (UUID, primary key with user_id)
 * - user_id TEXT (indexed, for per-profile isolation)
 * - name TEXT (user-facing label)
 * - query_string TEXT (raw query as entered by user)
 * - created_at INTEGER (epoch millis)
 * - updated_at INTEGER (epoch millis)
 *
 * Composite primary key (id, user_id) ensures profile isolation.
 * Unique index on (user_id, name) prevents duplicate names per profile.
 */
class Migration15To16 : AutoMigrationSpec
