package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v24 to v25 — adds `verb` column to `task_dependencies`.
 *
 * The column stores the semantic relationship type between two tasks:
 * BLOCKS (default), FOLLOWS_UP, DUPLICATES, FIXES, SUPERSEDES.
 *
 * No backfill is needed: existing rows get `verb = 'BLOCKS'` (the SQL default).
 * No index on `verb` is added speculatively — add `(to_task_id, verb)` only when
 * a concrete query pattern demonstrates the need (per BAN list).
 *
 * @see com.singularity.todo.feature.tasks.domain.model.DependencyVerb
 */
class Migration24To25 : AutoMigrationSpec
