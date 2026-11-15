package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v53 to v54 — the rich task description.
 *
 * Adds one nullable column, `tasks.description_html`, which carries the HTML the
 * rich editor writes. Room derives the `ALTER TABLE` from the entity; there is
 * nothing to move, because a row written before the upgrade has no rich
 * description and its plain `description` is read literally.
 *
 * The plain `description` column is untouched on purpose: it is the
 * `tasks_fts` content column, and the full-text index must keep seeing plain
 * text rather than markup.
 */
class Migration53To54 : AutoMigrationSpec
