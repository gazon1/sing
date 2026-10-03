package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v30 to v31 — adds polymorphic target to proposals.
 *
 * Adds to `ai_proposal`:
 * - `target_kind TEXT NOT NULL DEFAULT 'TASK'` — which entity type this proposal applies to
 * - `target_id TEXT` — the id of the target entity
 *
 * The new composite index `(user_id, target_kind, target_id)` replaces the old
 * single-column `task_id` index for efficient per-target, per-user queries.
 *
 * `task_id` is kept as a nullable column (Room does not support dropping a column
 * and referencing it in `onPostMigrate` in the same migration). It is now redundant
 * and will be removed in a future migration once all live data has been migrated.
 */
class Migration30To31 : AutoMigrationSpec
