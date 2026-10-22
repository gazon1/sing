package com.singularity.todo.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Migration from v45 to v46 — adds the nullable `series_id` column to tasks.
 *
 * A series is one piece of work scheduled on several dates: several task rows that
 * share a series id, each keeping its own date and its own completion state.
 *
 * ## Why this migration is written by hand
 *
 * Room's auto-migration would derive this change from the entity, and the derived
 * migration **rewrites the whole `tasks` table**: it creates `_new_tasks`, copies
 * every row, drops `tasks` and renames the copy back. That rewrite is not free of
 * side effects, and one of them destroys data the app never asked to lose:
 *
 * - `notes.task_id` is a foreign key onto `tasks(id)` with `ON DELETE SET NULL`.
 *   SQLite runs the foreign-key actions of a dropped table as an implicit
 *   `DELETE FROM tasks`, so every note that pointed at a task sees its
 *   `task_id` become `NULL`. The note survives; the relation does not.
 * - The rewrite copies the rows through a second table, so every row is written
 *   twice for a change that only needs a column added.
 *
 * The upgrade this migration performs is one column and one index. `ALTER TABLE
 * ... ADD COLUMN` writes neither the rows nor the relations, and the index is a
 * separate statement — SQLite builds it from the rows that are already there.
 *
 * ## Why the column is nullable
 *
 * Every task that existed before the upgrade reads back with `seriesId == null`,
 * which is exactly what "not part of a series" means. Nothing has to be
 * backfilled, and nothing can be lost.
 *
 * @see com.singularity.todo.docs.decisions.2026-10-22-task-series-materialized-copies
 */
class Migration45To46 : Migration(45, 46) {

    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `tasks` ADD COLUMN `series_id` TEXT")
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tasks_series_id` ON `tasks` (`series_id`)",
        )
    }
}
